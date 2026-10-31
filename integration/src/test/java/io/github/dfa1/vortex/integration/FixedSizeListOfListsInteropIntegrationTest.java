package io.github.dfa1.vortex.integration;

import dev.vortex.api.Expression;
import dev.vortex.api.ScanOptions;
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
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.FixedSizeListData;
import io.github.dfa1.vortex.writer.encode.ListData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.FixedSizeListVector;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A fixed-size list whose elements are variable-size lists (`list<list<T>>` with the outer one
/// fixed), against vortex-jni in both directions. Three validities nest here (the outer list, each
/// inner list, each element) and two element counts: the outer list's `rows * size` inner lists
/// and the inner lists' own, variable, element total. A decoder that sized either from the wrong
/// parent, or dropped one validity, fails here and nowhere in the flat tests.
class FixedSizeListOfListsInteropIntegrationTest {

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
            for (int size : new int[]{1, 3}) {
                for (Nulls outer : Nulls.values()) {
                    for (Nulls inner : new Nulls[]{Nulls.NONE_NULL, Nulls.EVERY_SEVENTH}) {
                        for (ElementNulls elements : new ElementNulls[]{
                                ElementNulls.NOT_NULLABLE, ElementNulls.EVERY_THIRD}) {
                            cases.add(Arguments.of(element, size, outer, inner, elements));
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} x {1}, outer {2}, inner {3}, elements {4}")
    @MethodSource("shapes")
    void javaWriter_jniReader_rowRangeAndFiltered(Element element, int size, Nulls outer, Nulls inner,
                                                 ElementNulls elements, @TempDir Path tmp) throws IOException {
        // Given
        List<List<List<Object>>> model = model(element, size, outer, inner, elements);
        Path file = tmp.resolve("java_fsl_of_lists.vtx");
        writeWithJava(file, element, size, outer, inner, elements, model);

        // When
        List<List<List<Object>>> full = readJni(file, 0, ROWS, null);
        List<List<List<Object>>> range = readJni(file, 333, 1_777, null);
        List<List<List<Object>>> filtered = readJni(file, 0, ROWS, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column("id"), Expression.literal(THRESHOLD)));

        // Then
        assertThat(full).containsExactlyInAnyOrderElementsOf(model);
        assertThat(range).containsExactlyInAnyOrderElementsOf(model.subList(333, 1_777));
        assertThat(filtered).containsExactlyInAnyOrderElementsOf(model.subList((int) THRESHOLD, ROWS));
    }

    @ParameterizedTest(name = "{0} x {1}, outer {2}, inner {3}, elements {4}")
    @MethodSource("shapes")
    void jniWriter_javaReader_everyRow(Element element, int size, Nulls outer, Nulls inner, ElementNulls elements,
                                      @TempDir Path tmp) throws IOException {
        // Given
        List<List<List<Object>>> model = model(element, size, outer, inner, elements);
        Path file = tmp.resolve("jni_fsl_of_lists.vtx");
        writeWithJni(file, element, size, outer, inner, elements, model);

        // When
        List<List<List<Object>>> result = readJava(file, element, size);

        // Then
        assertThat(result).containsExactlyElementsOf(model);
    }

    /// Every row of the column as the reader should see it: null, or `size` inner lists, each null
    /// or a list of elements, each null or a value. Inner list `q = row * size + k` has `q % 4`
    /// elements; the inner lists of a null outer row are empty and valid (they are never looked at).
    private static List<List<List<Object>>> model(Element element, int size, Nulls outer, Nulls inner,
                                                  ElementNulls elements) {
        List<List<List<Object>>> rows = new ArrayList<>();
        long flat = 0;
        for (long row = 0; row < ROWS; row++) {
            if (outer.isNull(row)) {
                rows.add(null);
                continue;
            }
            List<List<Object>> lists = new ArrayList<>();
            for (int k = 0; k < size; k++) {
                long q = row * size + k;
                if (inner.isNull(q)) {
                    lists.add(null);
                    continue;
                }
                List<Object> values = new ArrayList<>();
                for (int j = 0; j < q % 4; j++) {
                    values.add(elements.isNull(flat) ? null : element.value(q, j));
                    flat++;
                }
                lists.add(values);
            }
            rows.add(lists);
        }
        return rows;
    }

    private static void writeWithJava(Path file, Element element, int size, Nulls outer, Nulls inner,
                                      ElementNulls elements, List<List<List<Object>>> model) throws IOException {
        DType elementType = elements.nullableType() ? element.dtype.asNullable() : element.dtype;
        DType.Struct schema = new DType.Struct(List.of(ID, LISTS), List.of(DType.I64,
                new DType.FixedSizeList(new DType.List(elementType, inner != Nulls.NONE_NULL), size,
                        outer != Nulls.NONE_NULL)), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            for (int chunk = 0; chunk < CHUNKS; chunk++) {
                long[] ids = new long[CHUNK_ROWS];
                boolean[] outerValidity = new boolean[CHUNK_ROWS];
                boolean[] innerValidity = new boolean[CHUNK_ROWS * size];
                long[] offsets = new long[CHUNK_ROWS * size + 1];
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < CHUNK_ROWS; i++) {
                    List<List<Object>> row = model.get(chunk * CHUNK_ROWS + i);
                    ids[i] = (long) chunk * CHUNK_ROWS + i;
                    outerValidity[i] = row != null;
                    for (int k = 0; k < size; k++) {
                        List<Object> list = row == null ? List.of() : row.get(k);
                        int slot = i * size + k;
                        innerValidity[slot] = row == null || list != null;
                        if (list != null) {
                            values.addAll(list);
                        }
                        offsets[slot + 1] = values.size();
                    }
                }
                boolean[] elementValidity = new boolean[values.size()];
                for (int i = 0; i < elementValidity.length; i++) {
                    elementValidity[i] = values.get(i) != null;
                }
                Object packed = pack(element, values);
                Object leaves = elements.nullableType() ? new NullableData(packed, elementValidity) : packed;
                Object innerLists = new ListData(leaves, offsets, (long) CHUNK_ROWS * size);
                Object innerColumn = inner != Nulls.NONE_NULL ? new NullableData(innerLists, innerValidity)
                        : innerLists;
                Object outerLists = new FixedSizeListData(innerColumn, CHUNK_ROWS);
                sut.writeChunk(Map.of(ID, ids,
                        LISTS, outer != Nulls.NONE_NULL ? new NullableData(outerLists, outerValidity) : outerLists));
            }
        }
    }

    private static Object pack(Element element, List<Object> values) {
        int n = values.size();
        return switch (element) {
            case INT -> {
                int[] out = new int[n];
                for (int i = 0; i < n; i++) {
                    out[i] = (Integer) orZero(element, values.get(i));
                }
                yield out;
            }
            case DOUBLE -> {
                double[] out = new double[n];
                for (int i = 0; i < n; i++) {
                    out[i] = (Double) orZero(element, values.get(i));
                }
                yield out;
            }
            case LONG -> {
                long[] out = new long[n];
                for (int i = 0; i < n; i++) {
                    out[i] = (Long) orZero(element, values.get(i));
                }
                yield out;
            }
            case BOOL -> {
                boolean[] out = new boolean[n];
                for (int i = 0; i < n; i++) {
                    out[i] = (Boolean) orZero(element, values.get(i));
                }
                yield out;
            }
            case UTF8 -> {
                String[] out = new String[n];
                for (int i = 0; i < n; i++) {
                    out[i] = (String) orZero(element, values.get(i));
                }
                yield out;
            }
        };
    }

    private static Object orZero(Element element, Object value) {
        return value == null ? element.zero() : value;
    }

    @SuppressWarnings("unchecked")
    private static List<List<List<Object>>> readJni(Path file, long begin, long end, Expression filter)
            throws IOException {
        var builder = ScanOptions.builder()
                .projection(Expression.select(new String[]{"v"}, Expression.root()))
                .rowRangeBegin(begin)
                .rowRangeEnd(end);
        if (filter != null) {
            builder = builder.filter(filter);
        }
        List<List<List<Object>>> rows = new ArrayList<>();
        JniFiles.forEachBatch(file.toAbsolutePath().toUri().toString(), builder.build(), root -> {
            FixedSizeListVector vector = (FixedSizeListVector) root.getVector("v");
            for (int i = 0; i < root.getRowCount(); i++) {
                rows.add(vector.isNull(i) ? null : vector.getObject(i).stream()
                        .map(inner -> inner == null ? null : ((List<Object>) inner).stream()
                                .map(item -> item instanceof org.apache.arrow.vector.util.Text text
                                        ? text.toString() : item)
                                .collect(Collectors.toCollection(ArrayList::new)))
                        .collect(Collectors.toCollection(ArrayList::new)));
            }
        });
        return rows;
    }

    private static void writeWithJni(Path file, Element element, int size, Nulls outer, Nulls inner,
                                     ElementNulls elements, List<List<List<Object>>> model) throws IOException {
        FieldType itemType = elements.nullableType() ? FieldType.nullable(element.arrowType)
                : FieldType.notNullable(element.arrowType);
        FieldType innerType = inner != Nulls.NONE_NULL ? FieldType.nullable(ArrowType.List.INSTANCE)
                : FieldType.notNullable(ArrowType.List.INSTANCE);
        FieldType outerType = outer != Nulls.NONE_NULL ? FieldType.nullable(new ArrowType.FixedSizeList(size))
                : FieldType.notNullable(new ArrowType.FixedSizeList(size));
        Schema schema = new Schema(List.of(
                Field.notNullable("id", new ArrowType.Int(64, true)),
                new Field("v", outerType, List.of(
                        new Field("item", innerType, List.of(new Field("item", itemType, null)))))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int chunk = 0; chunk < CHUNKS; chunk++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    FixedSizeListVector outerVector = (FixedSizeListVector) root.getVector("v");
                    ids.allocateNew(CHUNK_ROWS);
                    outerVector.allocateNew();
                    ListVector innerVector = (ListVector) outerVector.getDataVector();
                    FieldVector items = innerVector.getDataVector();
                    int item = 0;
                    for (int i = 0; i < CHUNK_ROWS; i++) {
                        List<List<Object>> row = model.get(chunk * CHUNK_ROWS + i);
                        ids.setSafe(i, (long) chunk * CHUNK_ROWS + i);
                        if (row == null) {
                            outerVector.setNull(i);
                        } else {
                            outerVector.setNotNull(i);
                        }
                        for (int k = 0; k < size; k++) {
                            int slot = i * size + k;
                            List<Object> list = row == null ? List.of() : row.get(k);
                            if (list == null) {
                                innerVector.setNull(slot);
                                continue;
                            }
                            int start = innerVector.startNewValue(slot);
                            for (int j = 0; j < list.size(); j++) {
                                element.write(items, start + j, list.get(j));
                            }
                            item += list.size();
                            innerVector.endValue(slot, list.size());
                        }
                    }
                    items.setValueCount(item);
                    root.setRowCount(CHUNK_ROWS);
                    JniFiles.writeBatch(ALLOCATOR, writer, root);
                }
            }
        }
    }

    private static List<List<List<Object>>> readJava(Path file, Element element, int size) throws IOException {
        List<List<List<Object>>> rows = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
             Arena arena = Arena.ofConfined()) {
            reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all()).forEachRemaining(chunk -> {
                MaskedArray.Unwrapped outer = MaskedArray.unwrap(chunk.column(LISTS));
                FixedSizeListArray outerColumn = (FixedSizeListArray) outer.inner();
                MaskedArray.Unwrapped inner = MaskedArray.unwrap(outerColumn.elements());
                ListArray innerColumn = (ListArray) inner.inner();
                MaskedArray.Unwrapped leaves = MaskedArray.unwrap(innerColumn.elements());
                Array values = leaves.inner();
                boolean allNull = values instanceof NullArray;
                MemorySegment lanes = allNull || !element.lanes() ? null : values.materialize(arena);
                MemorySegment offsets = innerColumn.offsets().materialize(arena);
                int offsetBytes = ((DType.Primitive) innerColumn.offsets().dtype()).ptype().byteSize();
                for (long row = 0; row < outerColumn.length(); row++) {
                    if (outer.validity() != null && !outer.validity().getBoolean(row)) {
                        rows.add(null);
                        continue;
                    }
                    List<List<Object>> lists = new ArrayList<>();
                    for (int k = 0; k < size; k++) {
                        long slot = row * size + k;
                        if (inner.validity() != null && !inner.validity().getBoolean(slot)) {
                            lists.add(null);
                            continue;
                        }
                        List<Object> list = new ArrayList<>();
                        for (long flat = offset(offsets, offsetBytes, slot);
                             flat < offset(offsets, offsetBytes, slot + 1); flat++) {
                            boolean elementIsNull = allNull || leaves.validity() != null
                                    && !leaves.validity().getBoolean(flat);
                            list.add(elementIsNull ? null : element.get(values, lanes, flat));
                        }
                        lists.add(list);
                    }
                    rows.add(lists);
                }
            });
        }
        return rows;
    }

    private static long offset(MemorySegment offsets, int bytes, long index) {
        return switch (bytes) {
            case 1 -> offsets.get(ValueLayout.JAVA_BYTE, index);
            case 2 -> offsets.getAtIndex(VortexFormat.LE_SHORT, index);
            case 4 -> offsets.getAtIndex(VortexFormat.LE_INT, index);
            default -> offsets.getAtIndex(VortexFormat.LE_LONG, index);
        };
    }
}
