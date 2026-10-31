package io.github.dfa1.vortex.integration;

import dev.vortex.api.Expression;
import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.Element;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.ElementNulls;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.Nulls;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.ListData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// `vortex.list` against vortex-jni, with the corner cases of [FixedSizeListInteropIntegrationTest]:
/// null lists, nullable elements, and every element type. Lists here have lengths 0 to 3, so empty
/// lists sit between null and non-empty ones, and a null list holds no elements. Two decoders
/// dropped a validity child that Rust writes (`vortex.fixed_size_list`, `vortex.fsst`); this
/// checks that `vortex.list` and its elements keep every null, in both directions.
class ListInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName ID = ColumnName.of("id");
    private static final ColumnName LISTS = ColumnName.of("v");
    private static final int CHUNKS = 3;
    private static final int CHUNK_ROWS = 1_000;
    private static final int ROWS = CHUNKS * CHUNK_ROWS;
    private static final long THRESHOLD = 1_500L;

    static {
        NativeLoader.loadJni();
    }

    static Stream<Arguments> shapes() {
        List<Arguments> cases = new ArrayList<>();
        for (Element element : Element.values()) {
            for (Nulls nulls : Nulls.values()) {
                for (ElementNulls elementNulls : ElementNulls.values()) {
                    cases.add(Arguments.of(element, nulls, elementNulls));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, lists {1}, elements {2}")
    @MethodSource("shapes")
    void javaWriter_jniReader_rowRangeAndFiltered(Element element, Nulls nulls, ElementNulls elementNulls,
                                                 @TempDir Path tmp) throws IOException {
        // Given the list column nullable only when it holds nulls, as a writer would declare it
        Path file = tmp.resolve("java_list.vtx");
        writeWithJava(file, element, nulls, elementNulls);

        // When
        List<List<Object>> full = JniFiles.readColumn(file, "v", 0, ROWS, null);
        List<List<Object>> range = JniFiles.readColumn(file, "v", 333, 1_777, null);
        List<List<Object>> filtered = JniFiles.readColumn(file, "v", 0, ROWS, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column("id"), Expression.literal(THRESHOLD)));

        // Then
        assertThat(full).containsExactlyInAnyOrderElementsOf(expected(element, nulls, elementNulls, 0, ROWS));
        assertThat(range).containsExactlyInAnyOrderElementsOf(expected(element, nulls, elementNulls, 333, 1_777));
        assertThat(filtered).containsExactlyInAnyOrderElementsOf(
                expected(element, nulls, elementNulls, (int) THRESHOLD, ROWS));
    }

    @ParameterizedTest(name = "{0}, lists {1}, elements {2}")
    @MethodSource("shapes")
    void jniWriter_javaReader_everyRow(Element element, Nulls nulls, ElementNulls elementNulls, @TempDir Path tmp)
            throws IOException {
        // Given
        Path file = tmp.resolve("jni_list.vtx");
        writeWithJni(file, element, nulls, elementNulls);

        // When
        List<List<Object>> result = readJava(file, element);

        // Then
        assertThat(result).containsExactlyElementsOf(expected(element, nulls, elementNulls, 0, ROWS));
    }

    static int length(long row) {
        return (int) (row % 4);
    }

    /// Index in the flat elements of the first element of `row` (null lists hold none).
    private static long firstFlat(Nulls nulls, long row) {
        long flat = 0;
        for (long r = 0; r < row; r++) {
            flat += nulls.isNull(r) ? 0 : length(r);
        }
        return flat;
    }

    static List<List<Object>> expected(Element element, Nulls nulls, ElementNulls elementNulls, int from,
                                               int to) {
        List<List<Object>> rows = new ArrayList<>();
        long flat = firstFlat(nulls, from);
        for (int row = from; row < to; row++) {
            if (nulls.isNull(row)) {
                rows.add(null);
                continue;
            }
            List<Object> list = new ArrayList<>();
            for (int k = 0; k < length(row); k++) {
                list.add(elementNulls.isNull(flat + k) ? null : element.value(row, k));
            }
            flat += length(row);
            rows.add(list);
        }
        return rows;
    }

    private static void writeWithJava(Path file, Element element, Nulls nulls, ElementNulls elementNulls)
            throws IOException {
        boolean nullableLists = nulls != Nulls.NONE_NULL;
        DType elementType = elementNulls.nullableType() ? element.dtype.asNullable() : element.dtype;
        DType.Struct schema = new DType.Struct(List.of(ID, LISTS),
                List.of(DType.I64, new DType.List(elementType, nullableLists)), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            long flat = 0;
            for (int chunk = 0; chunk < CHUNKS; chunk++) {
                long first = (long) chunk * CHUNK_ROWS;
                long[] ids = new long[CHUNK_ROWS];
                long[] offsets = new long[CHUNK_ROWS + 1];
                boolean[] listValidity = new boolean[CHUNK_ROWS];
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < CHUNK_ROWS; i++) {
                    long row = first + i;
                    ids[i] = row;
                    listValidity[i] = !nulls.isNull(row);
                    int length = listValidity[i] ? length(row) : 0;
                    for (int k = 0; k < length; k++) {
                        values.add(elementNulls.isNull(flat + k) ? null : element.value(row, k));
                    }
                    flat += length;
                    offsets[i + 1] = offsets[i] + length;
                }
                Object packed = element.pack(values);
                boolean[] elementValidity = new boolean[values.size()];
                for (int i = 0; i < elementValidity.length; i++) {
                    elementValidity[i] = values.get(i) != null;
                }
                Object elements = elementNulls.nullableType() ? new NullableData(packed, elementValidity) : packed;
                Object lists = new ListData(elements, offsets, CHUNK_ROWS);
                sut.writeChunk(Map.of(ID, ids, LISTS, nullableLists ? new NullableData(lists, listValidity) : lists));
            }
        }
    }

    private static void writeWithJni(Path file, Element element, Nulls nulls, ElementNulls elementNulls)
            throws IOException {
        FieldType itemType = elementNulls.nullableType() ? FieldType.nullable(element.arrowType)
                : FieldType.notNullable(element.arrowType);
        FieldType listType = nulls != Nulls.NONE_NULL ? FieldType.nullable(ArrowType.List.INSTANCE)
                : FieldType.notNullable(ArrowType.List.INSTANCE);
        Schema schema = new Schema(List.of(
                Field.notNullable("id", new ArrowType.Int(64, true)),
                new Field("v", listType, List.of(new Field("item", itemType, null)))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            long flat = 0;
            for (int chunk = 0; chunk < CHUNKS; chunk++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    ListVector lists = (ListVector) root.getVector("v");
                    ids.allocateNew(CHUNK_ROWS);
                    lists.allocateNew();
                    FieldVector items = lists.getDataVector();
                    int item = 0;
                    for (int i = 0; i < CHUNK_ROWS; i++) {
                        long row = (long) chunk * CHUNK_ROWS + i;
                        ids.setSafe(i, row);
                        if (nulls.isNull(row)) {
                            lists.setNull(i);
                            continue;
                        }
                        int start = lists.startNewValue(i);
                        int length = length(row);
                        for (int k = 0; k < length; k++) {
                            element.write(items, start + k,
                                    elementNulls.isNull(flat + k) ? null : element.value(row, k));
                        }
                        flat += length;
                        item += length;
                        lists.endValue(i, length);
                    }
                    items.setValueCount(item);
                    root.setRowCount(CHUNK_ROWS);
                    JniFiles.writeBatch(ALLOCATOR, writer, root);
                }
            }
        }
    }

    private static List<List<Object>> readJava(Path file, Element element) throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
             Arena arena = Arena.ofConfined()) {
            reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all()).forEachRemaining(chunk -> {
                MaskedArray.Unwrapped unwrapped = MaskedArray.unwrap(chunk.column(LISTS));
                ListArray listColumn = (ListArray) unwrapped.inner();
                MaskedArray.Unwrapped unwrappedElements = MaskedArray.unwrap(listColumn.elements());
                Array inner = unwrappedElements.inner();
                boolean allNull = inner instanceof NullArray;
                MemorySegment lanes = allNull || !element.lanes() ? null : inner.materialize(arena);
                MemorySegment offsets = listColumn.offsets().materialize(arena);
                int offsetBytes = ((DType.Primitive) listColumn.offsets().dtype()).ptype().byteSize();
                for (long row = 0; row < listColumn.length(); row++) {
                    if (unwrapped.validity() != null && !unwrapped.validity().getBoolean(row)) {
                        rows.add(null);
                        continue;
                    }
                    long from = offset(offsets, offsetBytes, row);
                    long to = offset(offsets, offsetBytes, row + 1);
                    List<Object> list = new ArrayList<>();
                    for (long flat = from; flat < to; flat++) {
                        boolean elementIsNull = allNull || unwrappedElements.validity() != null
                                && !unwrappedElements.validity().getBoolean(flat);
                        list.add(elementIsNull ? null : element.get(inner, lanes, flat));
                    }
                    rows.add(list);
                }
            });
        }
        return rows;
    }

    static long offset(MemorySegment offsets, int bytes, long index) {
        return switch (bytes) {
            case 1 -> offsets.get(ValueLayout.JAVA_BYTE, index);
            case 2 -> offsets.getAtIndex(VortexFormat.LE_SHORT, index);
            case 4 -> offsets.getAtIndex(VortexFormat.LE_INT, index);
            default -> offsets.getAtIndex(VortexFormat.LE_LONG, index);
        };
    }
}
