package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.ImmutableScanOptions;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoDecimalMetadata;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.WriteRegistry;
import io.github.dfa1.vortex.writer.encode.BoolEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.EncodeContext;
import io.github.dfa1.vortex.writer.encode.EncodeNode;
import io.github.dfa1.vortex.writer.encode.EncodeResult;
import io.github.dfa1.vortex.writer.encode.EncodedBuffer;
import io.github.dfa1.vortex.writer.encode.EncodingEncoder;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Rust 0.95's read rules for a `vortex.decimal` node (`StoredDecimal::deserialize` and
/// `WidenArray::from_stored_decimal`, #662). A test-only encoder writes each node shape by hand,
/// and vortex-jni is the oracle: both readers must reject what it rejects and read what it reads.
class DecimalReadRulesInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final ColumnName D = ColumnName.of("d");
    private static final int ROWS = 64;
    private static final int PRECISION = 9;
    /// The smallest unscaled value precision 9 cannot hold.
    private static final long TOO_WIDE = 1_000_000_000L;

    static {
        NativeLoader.loadJni();
    }

    /// One hand-written node: the stored width (log2 of its bytes), bytes appended past the rows,
    /// whether a non-nullable column still gets a validity child, the buffer's declared alignment,
    /// and an unscaled value written at row 3 (null in a nullable column) and row 5 (valid).
    record Node(int valuesType, int extraBytes, boolean validityOnNonNullable, int alignment, long row3, long row5) {
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
                // Rust requires exactly rows × width bytes; Java rejected only a short buffer
                Arguments.of("buffer one value too long", false, new Node(2, 4, false, 4, 3, 5)),
                // fixed_width::deserialize_validity: a validity child needs a nullable dtype
                Arguments.of("validity child on a non-nullable column", false, new Node(2, 0, true, 4, 3, 5)),
                // a legacy width is scanned, and a valid value outside the precision fails it
                Arguments.of("legacy width, valid value too wide", true, new Node(3, 0, false, 8, 3, TOO_WIDE)),
                // DecimalData::validate: the buffer must be aligned to the value width
                Arguments.of("buffer under-aligned", false, new Node(2, 0, false, 1, 3, 5)));
    }

    static Stream<Arguments> read() {
        return Stream.of(
                Arguments.of("canonical width", true, new Node(2, 0, false, 4, 3, 5)),
                // a canonical node is trusted: its values are not scanned against the precision (nullable,
                // as only that path lets the writer pass a value its precision cannot hold)
                Arguments.of("canonical width, value too wide", true, new Node(2, 0, false, 4, 3, TOO_WIDE)),
                Arguments.of("legacy wider width", true, new Node(3, 0, false, 8, 3, 5)),
                Arguments.of("legacy narrower width", true, new Node(1, 0, false, 2, 3, 5)),
                // only valid values are scanned: a null row may hold anything
                Arguments.of("legacy width, null row too wide", true, new Node(3, 0, false, 8, TOO_WIDE, 5)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void bothReadersReject(String name, boolean nullable, Node node, @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("d.vortex");
        write(file, nullable, node);

        // When / Then
        assertThatThrownBy(() -> readWithJni(file)).as("vortex-jni").isInstanceOf(Exception.class);
        assertThatThrownBy(() -> readWithJava(file)).as("vortex-java").isInstanceOf(VortexException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("read")
    void bothReadersReadTheSameValues(String name, boolean nullable, Node node, @TempDir Path tmp)
            throws IOException {
        // Given
        Path file = tmp.resolve("d.vortex");
        write(file, nullable, node);
        List<BigDecimal> expected = readWithJni(file);

        // When
        List<BigDecimal> result = readWithJava(file);

        // Then
        assertThat(result).isEqualTo(expected);
        assertThat(result.get(5)).isEqualTo(BigDecimal.valueOf(node.row5()));
    }

    private static long unscaled(int row, Node node) {
        if (row == 3) {
            return node.row3();
        }
        if (row == 5) {
            return node.row5();
        }
        return row * 1000L;
    }

    private static void write(Path path, boolean nullable, Node node) throws IOException {
        BigDecimal[] values = new BigDecimal[ROWS];
        boolean[] validity = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            validity[i] = !nullable || i != 3;
            values[i] = BigDecimal.valueOf(unscaled(i, node));
        }
        Object data = nullable ? new NullableData(values, validity) : values;
        DType.Struct schema = new DType.Struct(List.of(D), List.of(DType.Decimal.of(PRECISION, 0, nullable)), false);
        WriteRegistry registry = WriteRegistry.builder()
                .register(new StoredDecimalEncoder(node))
                .register(new BoolEncodingEncoder())
                .build();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema,
                     WriteOptions.defaults().withZoneMaps(false), registry)) {
            writer.writeChunk(Map.of(D, data));
        }
    }

    private static List<BigDecimal> readWithJava(Path file) throws IOException {
        List<BigDecimal> out = new ArrayList<>();
        try (var reader = VortexReader.open(file);
             var scan = reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            while (scan.hasNext()) {
                try (var chunk = scan.next()) {
                    Array column = chunk.column(D);
                    for (int i = 0; i < chunk.rowCount(); i++) {
                        if (column instanceof MaskedArray masked) {
                            out.add(masked.isValid(i) ? ((DecimalArray) masked.inner()).getDecimal(i) : null);
                        } else {
                            out.add(((DecimalArray) column).getDecimal(i));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static List<BigDecimal> readWithJni(Path file) throws IOException {
        var options = ImmutableScanOptions.builder()
                .projection(Expression.select(new String[]{"d"}, Expression.root())).ordered(true).build();
        List<BigDecimal> out = new ArrayList<>();
        Scan scan = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString()).scan(options);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ArrowAllocation.rootAllocator())) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    FieldVector vector = root.getVector("d");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        out.add((BigDecimal) vector.getObject(i));
                    }
                }
            }
        }
        return out;
    }

    /// Writes `vortex.decimal` nodes exactly as [Node] describes, whatever the precision allows.
    private record StoredDecimalEncoder(Node node) implements EncodingEncoder {

        @Override
        public EncodingId encodingId() {
            return EncodingId.VORTEX_DECIMAL;
        }

        @Override
        public boolean accepts(DType dtype) {
            return dtype instanceof DType.Decimal;
        }

        @Override
        public boolean acceptsNullable(DType dtype) {
            return dtype instanceof DType.Decimal;
        }

        @Override
        public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
            BigDecimal[] values;
            boolean[] validity = null;
            if (data instanceof NullableData(Object v, boolean[] valid)) {
                values = (BigDecimal[]) v;
                validity = valid;
            } else {
                values = (BigDecimal[]) data;
            }
            if (validity == null && node.validityOnNonNullable()) {
                validity = new boolean[values.length];
                Arrays.fill(validity, true);
            }
            int width = 1 << node.valuesType();
            byte[] bytes = new byte[values.length * width + node.extraBytes()];
            for (int i = 0; i < values.length; i++) {
                byte[] be = values[i].unscaledValue().toByteArray();
                for (int b = 0; b < width; b++) {
                    int src = be.length - 1 - b;
                    bytes[i * width + b] = src >= 0 ? be[src] : (byte) (be[0] < 0 ? -1 : 0);
                }
            }
            MemorySegment meta = MemorySegment.ofArray(new ProtoDecimalMetadata(node.valuesType()).encode());
            List<EncodedBuffer> buffers = new ArrayList<>();
            buffers.add(new EncodedBuffer(MemorySegment.ofArray(bytes), node.alignment()));
            EncodeNode[] children = new EncodeNode[0];
            if (validity != null) {
                EncodeResult bool = new BoolEncodingEncoder().encode(DTypes.BOOL, validity, ctx);
                children = new EncodeNode[]{EncodeNode.remapBufferIndices(bool.rootNode(), 1)};
                buffers.addAll(bool.encodedBuffers());
            }
            return new EncodeResult(new EncodeNode(EncodingId.VORTEX_DECIMAL, meta, children, new int[]{0}),
                    List.copyOf(buffers), null, null);
        }
    }
}
