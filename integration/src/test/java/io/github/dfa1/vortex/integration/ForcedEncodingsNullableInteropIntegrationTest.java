package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.Element;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.Nulls;
import io.github.dfa1.vortex.integration.RustWritesNullableColumnsInteropIntegrationTest.Shape;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.writer.ColumnEncoding;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Nullable columns in every encoding the Java writer can be forced to, read back by vortex-jni
/// and by vortex-java. Rust's compressor never picks some of these (Pco, Zstd and OnPair need the
/// compact preset, RLE needs long runs), so the Java writer forces each one onto data that has
/// nulls in it: none, some and all. This covers the decoders on Rust's side and the encoders on
/// ours; vortex-java's own decoders read the same file, so a validity one side writes and the
/// other drops fails here too.
class ForcedEncodingsNullableInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName ID = ColumnName.of("id");
    private static final ColumnName VALUE = ColumnName.of("v");
    private static final int ROWS = 3_000;

    /// Encodings that actually ended up in a file, so an encoding the forced run silently fell
    /// back from (the cascade keeps a canonical encoding when the forced one does not pay) fails
    /// [#everyForcedEncodingWasWritten] instead of passing unnoticed.
    private static final Set<String> WRITTEN = ConcurrentHashMap.newKeySet();

    /// Which element types each encoding takes.
    private static final Map<EncodingId, List<Element>> ENCODINGS = Map.ofEntries(
            Map.entry(EncodingId.FASTLANES_RLE, List.of(Element.INT, Element.LONG, Element.DOUBLE, Element.BOOL)),
            Map.entry(EncodingId.VORTEX_PCO, List.of(Element.INT, Element.LONG, Element.DOUBLE)),
            Map.entry(EncodingId.VORTEX_ZSTD, List.of(Element.INT, Element.LONG, Element.DOUBLE, Element.UTF8)),
            Map.entry(EncodingId.VORTEX_ALP, List.of(Element.DOUBLE)),
            Map.entry(EncodingId.VORTEX_ALPRD, List.of(Element.DOUBLE)),
            Map.entry(EncodingId.VORTEX_ZIGZAG, List.of(Element.INT, Element.LONG)),
            Map.entry(EncodingId.VORTEX_ONPAIR, List.of(Element.UTF8)),
            Map.entry(EncodingId.VORTEX_FSST, List.of(Element.UTF8)),
            Map.entry(EncodingId.FASTLANES_DELTA, List.of(Element.INT, Element.LONG)),
            Map.entry(EncodingId.FASTLANES_BITPACKED, List.of(Element.INT, Element.LONG)),
            Map.entry(EncodingId.FASTLANES_FOR, List.of(Element.INT, Element.LONG)),
            Map.entry(EncodingId.VORTEX_DICT, List.of(Element.INT, Element.LONG, Element.DOUBLE, Element.UTF8)),
            Map.entry(EncodingId.VORTEX_SPARSE, List.of(Element.INT, Element.LONG, Element.DOUBLE, Element.UTF8)),
            Map.entry(EncodingId.VORTEX_RUNEND, List.of(Element.INT, Element.LONG, Element.DOUBLE, Element.UTF8)));

    static {
        NativeLoader.loadJni();
    }

    static Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        ENCODINGS.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey(
                java.util.Comparator.comparing(EncodingId::toString))).forEach(entry -> {
            for (Element element : entry.getValue()) {
                for (Shape shape : Shape.values()) {
                    for (Nulls nulls : Nulls.values()) {
                        // #519: a forced vortex.sparse over data with no value to patch (every row null,
                        // or every row the same) writes zero patches, which Rust rejects when it reads
                        if (entry.getKey() == EncodingId.VORTEX_SPARSE
                                && (nulls == Nulls.ALL_NULL || shape == Shape.CONSTANT && nulls == Nulls.NONE_NULL)) {
                            continue;
                        }
                        cases.add(Arguments.of(entry.getKey(), element, shape, nulls));
                    }
                }
            }
        });
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, {1}, {2}, {3}")
    @MethodSource("cases")
    void javaWriter_bothReaders_keepEveryNull(EncodingId encoding, Element element, Shape shape, Nulls nulls,
                                             @TempDir Path tmp) throws IOException {
        // Given
        List<Object> expected = new ArrayList<>();
        boolean[] validity = new boolean[ROWS];
        for (long row = 0; row < ROWS; row++) {
            validity[(int) row] = !nulls.isNull(row);
            expected.add(nulls.isNull(row) ? null : element.value(shape.key.apply(row), 0));
        }
        Path file = tmp.resolve("forced.vtx");
        DType.Struct schema = new DType.Struct(List.of(ID, VALUE),
                List.of(DType.I64, element.dtype.asNullable()), false);
        WriteOptions options = WriteOptions.defaults().withoutEditions()
                .withColumnEncoding(VALUE, ColumnEncoding.candidates(candidates(encoding)));
        long[] ids = new long[ROWS];
        for (int i = 0; i < ROWS; i++) {
            ids[i] = i;
        }
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, options)) {
            sut.writeChunk(Map.of(ID, ids, VALUE,
                    new NullableData(ListInteropIntegrationTest.pack(element, expected), validity)));
        }

        // When
        List<Object> byRust = readJni(file);
        List<Object> byJava = readJava(file, element);

        // Then
        assertThat(byRust).as("read by vortex-jni").containsExactlyElementsOf(expected);
        assertThat(byJava).as("read by vortex-java").containsExactlyElementsOf(expected);
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            if (VortexInspector.inspect(reader).contains(encoding.toString())) {
                WRITTEN.add(encoding.toString());
            }
        }
    }

    /// ALP-RD stores its left parts bit-packed, so a candidate set naming only ALP-RD has no encoder
    /// for its own child (Rust's candidate sets name the children too).
    private static EncodingId[] candidates(EncodingId encoding) {
        return encoding == EncodingId.VORTEX_ALPRD
                ? new EncodingId[]{encoding, EncodingId.FASTLANES_BITPACKED}
                : new EncodingId[]{encoding};
    }

    @AfterAll
    static void everyForcedEncodingWasWritten() {
        // Vortex's own bookkeeping encodings are skipped: they are not what the sweep forces
        assertThat(WRITTEN).as("encodings that reached a file")
                .containsAll(ENCODINGS.keySet().stream().map(EncodingId::toString).toList());
    }

    private static List<Object> readJni(Path file) throws IOException {
        ScanOptions options = ScanOptions.builder()
                .projection(Expression.select(new String[]{"v"}, Expression.root())).build();
        List<Object> rows = new ArrayList<>();
        Scan scan = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString()).scan(options);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    FieldVector vector = root.getVector("v");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        Object value = vector.isNull(i) ? null : vector.getObject(i);
                        rows.add(value instanceof org.apache.arrow.vector.util.Text text ? text.toString() : value);
                    }
                }
            }
        }
        return rows;
    }

    private static List<Object> readJava(Path file, Element element) throws IOException {
        List<Object> rows = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
             Arena arena = Arena.ofConfined()) {
            reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all()).forEachRemaining(chunk -> {
                MaskedArray.Unwrapped column = MaskedArray.unwrap(chunk.column(VALUE));
                Array inner = column.inner();
                boolean allNull = inner instanceof NullArray;
                MemorySegment lanes = allNull || !element.lanes() ? null : inner.materialize(arena);
                for (long i = 0; i < inner.length(); i++) {
                    boolean isNull = allNull || column.validity() != null && !column.validity().getBoolean(i);
                    rows.add(isNull ? null : element.get(inner, lanes, i));
                }
            });
        }
        return rows;
    }
}
