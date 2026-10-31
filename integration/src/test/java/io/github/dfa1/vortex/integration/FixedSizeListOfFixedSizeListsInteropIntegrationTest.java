package io.github.dfa1.vortex.integration;

import dev.vortex.api.Expression;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.Element;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.ElementNulls;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.Nulls;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.FixedSizeListData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.FixedSizeListVector;
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
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A fixed-size list of fixed-size lists, against vortex-jni in both directions: no offsets
/// anywhere, so every row is `outer * inner` elements deep, and three validities nest (the outer
/// list, each inner list, each element). An inner size of 0 leaves no elements at all.
class FixedSizeListOfFixedSizeListsInteropIntegrationTest {

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
            for (int outerSize : new int[]{1, 3}) {
                for (int innerSize : new int[]{0, 1, 3}) {
                    for (Nulls outer : Nulls.values()) {
                        for (Nulls inner : new Nulls[]{Nulls.NONE_NULL, Nulls.EVERY_SEVENTH}) {
                            for (ElementNulls elements : new ElementNulls[]{
                                    ElementNulls.NOT_NULLABLE, ElementNulls.EVERY_THIRD}) {
                                cases.add(Arguments.of(element, outerSize, innerSize, outer, inner, elements));
                            }
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, {1} x {2}, outer {3}, inner {4}, elements {5}")
    @MethodSource("shapes")
    void javaWriter_jniReader_rowRangeAndFiltered(Element element, int outerSize, int innerSize, Nulls outer,
                                                 Nulls inner, ElementNulls elements, @TempDir Path tmp)
            throws IOException {
        // Given
        List<List<List<Object>>> model = model(element, outerSize, innerSize, outer, inner, elements);
        Path file = tmp.resolve("java_fsl_of_fsl.vtx");
        writeWithJava(file, element, outerSize, innerSize, outer, inner, elements, model);

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

    @ParameterizedTest(name = "{0}, {1} x {2}, outer {3}, inner {4}, elements {5}")
    @MethodSource("shapes")
    void jniWriter_javaReader_everyRow(Element element, int outerSize, int innerSize, Nulls outer, Nulls inner,
                                      ElementNulls elements, @TempDir Path tmp) throws IOException {
        // Given
        List<List<List<Object>>> model = model(element, outerSize, innerSize, outer, inner, elements);
        Path file = tmp.resolve("jni_fsl_of_fsl.vtx");
        writeWithJni(file, element, outerSize, innerSize, outer, inner, elements, model);

        // When
        List<List<List<Object>>> result = readJava(file, element, outerSize, innerSize);

        // Then
        assertThat(result).containsExactlyElementsOf(model);
    }

    /// Every row as the reader should see it: null, or `outerSize` inner lists, each null or
    /// `innerSize` elements, each null or a value. Element `b` of inner list `a` of `row` is numbered
    /// by its position in the flat elements, so a shifted stride moves every later element.
    private static List<List<List<Object>>> model(Element element, int outerSize, int innerSize, Nulls outer,
                                                  Nulls inner, ElementNulls elements) {
        List<List<List<Object>>> rows = new ArrayList<>();
        for (long row = 0; row < ROWS; row++) {
            if (outer.isNull(row)) {
                rows.add(null);
                continue;
            }
            List<List<Object>> lists = new ArrayList<>();
            for (int a = 0; a < outerSize; a++) {
                long slot = row * outerSize + a;
                if (inner.isNull(slot)) {
                    lists.add(null);
                    continue;
                }
                List<Object> values = new ArrayList<>();
                for (int b = 0; b < innerSize; b++) {
                    values.add(elements.isNull(slot * innerSize + b) ? null : element.value(slot, b));
                }
                lists.add(values);
            }
            rows.add(lists);
        }
        return rows;
    }

    private static void writeWithJava(Path file, Element element, int outerSize, int innerSize, Nulls outer,
                                      Nulls inner, ElementNulls elements, List<List<List<Object>>> model)
            throws IOException {
        DType elementType = elements.nullableType() ? element.dtype.asNullable() : element.dtype;
        DType.Struct schema = new DType.Struct(List.of(ID, LISTS), List.of(DType.I64,
                new DType.FixedSizeList(new DType.FixedSizeList(elementType, innerSize, inner != Nulls.NONE_NULL),
                        outerSize, outer != Nulls.NONE_NULL)), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            for (int chunk = 0; chunk < CHUNKS; chunk++) {
                long[] ids = new long[CHUNK_ROWS];
                boolean[] outerValidity = new boolean[CHUNK_ROWS];
                boolean[] innerValidity = new boolean[CHUNK_ROWS * outerSize];
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < CHUNK_ROWS; i++) {
                    List<List<Object>> row = model.get(chunk * CHUNK_ROWS + i);
                    ids[i] = (long) chunk * CHUNK_ROWS + i;
                    outerValidity[i] = row != null;
                    for (int a = 0; a < outerSize; a++) {
                        List<Object> list = row == null ? null : row.get(a);
                        // the slots of a null list are still in the flat array, as zeros
                        innerValidity[i * outerSize + a] = row == null || list != null;
                        for (int b = 0; b < innerSize; b++) {
                            values.add(list == null ? element.zero() : list.get(b));
                        }
                    }
                }
                boolean[] elementValidity = new boolean[values.size()];
                for (int i = 0; i < elementValidity.length; i++) {
                    elementValidity[i] = values.get(i) != null;
                }
                Object leaves = pack(element, values);
                Object leafColumn = elements.nullableType() ? new NullableData(leaves, elementValidity) : leaves;
                Object innerLists = new FixedSizeListData(leafColumn, (long) CHUNK_ROWS * outerSize);
                Object innerColumn = inner != Nulls.NONE_NULL ? new NullableData(innerLists, innerValidity)
                        : innerLists;
                Object outerLists = new FixedSizeListData(innerColumn, CHUNK_ROWS);
                sut.writeChunk(Map.of(ID, ids,
                        LISTS, outer != Nulls.NONE_NULL ? new NullableData(outerLists, outerValidity) : outerLists));
            }
        }
    }

    /// The dense array of `values`, with the element's zero (or empty) where one is null.
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

    private static void writeWithJni(Path file, Element element, int outerSize, int innerSize, Nulls outer,
                                     Nulls inner, ElementNulls elements, List<List<List<Object>>> model)
            throws IOException {
        FieldType itemType = elements.nullableType() ? FieldType.nullable(element.arrowType)
                : FieldType.notNullable(element.arrowType);
        FieldType innerType = inner != Nulls.NONE_NULL ? FieldType.nullable(new ArrowType.FixedSizeList(innerSize))
                : FieldType.notNullable(new ArrowType.FixedSizeList(innerSize));
        FieldType outerType = outer != Nulls.NONE_NULL ? FieldType.nullable(new ArrowType.FixedSizeList(outerSize))
                : FieldType.notNullable(new ArrowType.FixedSizeList(outerSize));
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
                    FixedSizeListVector innerVector = (FixedSizeListVector) outerVector.getDataVector();
                    FieldVector items = innerVector.getDataVector();
                    for (int i = 0; i < CHUNK_ROWS; i++) {
                        List<List<Object>> row = model.get(chunk * CHUNK_ROWS + i);
                        ids.setSafe(i, (long) chunk * CHUNK_ROWS + i);
                        if (row == null) {
                            outerVector.setNull(i);
                        } else {
                            outerVector.setNotNull(i);
                        }
                        for (int a = 0; a < outerSize; a++) {
                            int slot = i * outerSize + a;
                            List<Object> list = row == null ? null : row.get(a);
                            if (row != null && list == null) {
                                innerVector.setNull(slot);
                            } else {
                                innerVector.setNotNull(slot);
                            }
                            for (int b = 0; b < innerSize; b++) {
                                element.write(items, slot * innerSize + b, list == null ? element.zero() : list.get(b));
                            }
                        }
                    }
                    root.setRowCount(CHUNK_ROWS);
                    JniFiles.writeBatch(ALLOCATOR, writer, root);
                }
            }
        }
    }

    private static List<List<List<Object>>> readJava(Path file, Element element, int outerSize, int innerSize)
            throws IOException {
        List<List<List<Object>>> rows = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
             Arena arena = Arena.ofConfined()) {
            reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all()).forEachRemaining(chunk -> {
                MaskedArray.Unwrapped outer = MaskedArray.unwrap(chunk.column(LISTS));
                FixedSizeListArray outerColumn = (FixedSizeListArray) outer.inner();
                MaskedArray.Unwrapped inner = MaskedArray.unwrap(outerColumn.elements());
                FixedSizeListArray innerColumn = (FixedSizeListArray) inner.inner();
                MaskedArray.Unwrapped leaves = MaskedArray.unwrap(innerColumn.elements());
                Array values = leaves.inner();
                boolean allNull = values instanceof NullArray;
                MemorySegment lanes = allNull || !element.lanes() ? null : values.materialize(arena);
                for (long row = 0; row < outerColumn.length(); row++) {
                    if (outer.validity() != null && !outer.validity().getBoolean(row)) {
                        rows.add(null);
                        continue;
                    }
                    List<List<Object>> lists = new ArrayList<>();
                    for (int a = 0; a < outerSize; a++) {
                        long slot = row * outerSize + a;
                        if (inner.validity() != null && !inner.validity().getBoolean(slot)) {
                            lists.add(null);
                            continue;
                        }
                        List<Object> list = new ArrayList<>();
                        for (int b = 0; b < innerSize; b++) {
                            long flat = slot * innerSize + b;
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
}
