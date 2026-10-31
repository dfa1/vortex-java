package io.github.dfa1.vortex.integration;

import dev.vortex.api.Expression;
import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.FixedSizeListData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.FixedSizeListVector;
import org.apache.arrow.vector.types.FloatingPointPrecision;
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
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// `vortex.fixed_size_list` against vortex-jni, in both directions. The encoding has a decoder, an
/// encoder and a Rust conformance file, but no test had Java write a fixed-size list for Rust to
/// read. A full scan never prunes or slices, so every shape is also read through a row range and a
/// filter on a sibling column: a slice of a list column must move `rows * fixedSize` elements, and
/// a filter must keep each list's elements together with its row.
class FixedSizeListInteropIntegrationTest {

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

    /// The element types a list is checked with: 4-, 8- and 8-byte lanes, the second a float.
    enum Element {
        INT(DType.I32, new ArrowType.Int(32, true)),
        DOUBLE(DType.F64, new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
        LONG(DType.I64, new ArrowType.Int(64, true)),
        BOOL(DType.BOOL, ArrowType.Bool.INSTANCE),
        UTF8(DType.UTF8, ArrowType.Utf8.INSTANCE);

        final DType dtype;
        final ArrowType arrowType;

        Element(DType dtype, ArrowType arrowType) {
            this.dtype = dtype;
            this.arrowType = arrowType;
        }

        /// Distinct per row and per position, negative for odd positions, so a slice that is off by
        /// one element or by one row never reads back equal.
        Object value(long row, int position) {
            return switch (this) {
                case INT -> (int) (row * 31 + position * 7L - 500);
                case DOUBLE -> row * 0.5 - position;
                case LONG -> (position % 2 == 0 ? 1 : -1) * (row * 1_000_000_007L + position);
                case BOOL -> (row + position) % 3 != 0;
                // empty and non-ASCII strings among the plain ones: a length or an encoding slip shows
                case UTF8 -> position == 0 && row % 5 == 0 ? "" : "r" + row + "/" + position + (row % 11 == 0 ? "é€" : "");
            };
        }

        /// Every row of `column` as a Java read of `file` returns it, `null` where the row is null.
        List<Object> readColumn(Path file, ColumnName column) throws IOException {
            List<Object> rows = new ArrayList<>();
            try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
                 Arena arena = Arena.ofConfined()) {
                reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all()).forEachRemaining(chunk -> {
                    MaskedArray.Unwrapped unwrapped = MaskedArray.unwrap(chunk.column(column));
                    Array inner = unwrapped.inner();
                    boolean allNull = inner instanceof NullArray;
                    MemorySegment flat = allNull || !lanes() ? null : inner.materialize(arena);
                    for (long i = 0; i < inner.length(); i++) {
                        boolean isNull = allNull || unwrapped.validity() != null && !unwrapped.validity().getBoolean(i);
                        rows.add(isNull ? null : get(inner, flat, i));
                    }
                });
            }
            return rows;
        }

        /// The dense array of `values`, zero (or empty) where an element is null.
        Object pack(List<Object> values) {
            int n = values.size();
            return switch (this) {
                case INT -> {
                    int[] out = new int[n];
                    for (int i = 0; i < n; i++) {
                        out[i] = (Integer) orZero(values.get(i));
                    }
                    yield out;
                }
                case DOUBLE -> {
                    double[] out = new double[n];
                    for (int i = 0; i < n; i++) {
                        out[i] = (Double) orZero(values.get(i));
                    }
                    yield out;
                }
                case LONG -> {
                    long[] out = new long[n];
                    for (int i = 0; i < n; i++) {
                        out[i] = (Long) orZero(values.get(i));
                    }
                    yield out;
                }
                case BOOL -> {
                    boolean[] out = new boolean[n];
                    for (int i = 0; i < n; i++) {
                        out[i] = (Boolean) orZero(values.get(i));
                    }
                    yield out;
                }
                case UTF8 -> {
                    String[] out = new String[n];
                    for (int i = 0; i < n; i++) {
                        out[i] = (String) orZero(values.get(i));
                    }
                    yield out;
                }
            };
        }

        /// `value`, or this element's zero (or empty) value when it is null.
        Object orZero(Object value) {
            return value == null ? zero() : value;
        }

        /// The flat elements of `rows` consecutive lists starting at `firstRow`.
        Object flat(long firstRow, int rows, int size) {
            return switch (this) {
                case INT -> {
                    int[] flat = new int[rows * size];
                    for (int i = 0; i < flat.length; i++) {
                        flat[i] = (Integer) value(firstRow + i / size, i % size);
                    }
                    yield flat;
                }
                case DOUBLE -> {
                    double[] flat = new double[rows * size];
                    for (int i = 0; i < flat.length; i++) {
                        flat[i] = (Double) value(firstRow + i / size, i % size);
                    }
                    yield flat;
                }
                case LONG -> {
                    long[] flat = new long[rows * size];
                    for (int i = 0; i < flat.length; i++) {
                        flat[i] = (Long) value(firstRow + i / size, i % size);
                    }
                    yield flat;
                }
                case BOOL -> {
                    boolean[] flat = new boolean[rows * size];
                    for (int i = 0; i < flat.length; i++) {
                        flat[i] = (Boolean) value(firstRow + i / size, i % size);
                    }
                    yield flat;
                }
                case UTF8 -> {
                    String[] flat = new String[rows * size];
                    for (int i = 0; i < flat.length; i++) {
                        flat[i] = (String) value(firstRow + i / size, i % size);
                    }
                    yield flat;
                }
            };
        }

        Object zero() {
            return switch (this) {
                case INT -> 0;
                case DOUBLE -> 0.0;
                case LONG -> 0L;
                case BOOL -> false;
                case UTF8 -> "";
            };
        }

        /// Bool and Utf8 are read through their array's accessor, the rest as little-endian lanes.
        boolean lanes() {
            return this != BOOL && this != UTF8;
        }

        Object get(Array inner, MemorySegment lanes, long index) {
            return switch (this) {
                case BOOL -> ((BoolArray) inner).getBoolean(index);
                case UTF8 -> ((VarBinArray) inner).getString(index);
                default -> read(lanes, index);
            };
        }

        Object read(MemorySegment segment, long index) {
            return switch (this) {
                case INT -> segment.getAtIndex(VortexFormat.LE_INT, index);
                case DOUBLE -> segment.getAtIndex(VortexFormat.LE_DOUBLE, index);
                case LONG -> segment.getAtIndex(VortexFormat.LE_LONG, index);
                case BOOL, UTF8 -> throw new IllegalStateException(this + " has no lanes");
            };
        }

        void write(FieldVector items, int index, Object value) {
            switch (items) {
                case IntVector vector when value == null -> vector.setNull(index);
                case Float8Vector vector when value == null -> vector.setNull(index);
                case BigIntVector vector when value == null -> vector.setNull(index);
                case BitVector vector when value == null -> vector.setNull(index);
                case VarCharVector vector when value == null -> vector.setNull(index);
                case BitVector vector -> vector.setSafe(index, (Boolean) value ? 1 : 0);
                case VarCharVector vector -> vector.setSafe(index, ((String) value).getBytes(StandardCharsets.UTF_8));
                case IntVector vector -> vector.setSafe(index, (Integer) value);
                case Float8Vector vector -> vector.setSafe(index, (Double) value);
                case BigIntVector vector -> vector.setSafe(index, (Long) value);
                default -> throw new IllegalStateException("unexpected vector " + items.getClass());
            }
        }
    }

    static Stream<Arguments> shapes() {
        List<Arguments> cases = new ArrayList<>();
        for (Element element : Element.values()) {
            for (int size : new int[]{1, 3, 16}) {
                cases.add(Arguments.of(element, size, WriteOptions.defaults()));
                cases.add(Arguments.of(element, size, WriteOptions.cascading(3)));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} x {1} / {2}")
    @MethodSource("shapes")
    void javaWriter_jniReader_fullRangeAndFiltered(Element element, int size, WriteOptions options,
                                                  @TempDir Path tmp) throws IOException {
        // Given three chunks of lists, each row holding `size` elements derived from its id
        Path file = tmp.resolve("java_fsl.vtx");
        DType.Struct schema = schema(element, size);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, options)) {
            for (int chunk = 0; chunk < CHUNKS; chunk++) {
                long first = (long) chunk * CHUNK_ROWS;
                long[] ids = new long[CHUNK_ROWS];
                for (int i = 0; i < CHUNK_ROWS; i++) {
                    ids[i] = first + i;
                }
                sut.writeChunk(Map.of(
                        ID, ids,
                        LISTS, new FixedSizeListData(element.flat(first, CHUNK_ROWS, size), CHUNK_ROWS)));
            }
        }

        // When Rust reads everything, a row range that cuts across chunks, and a filter on the id
        List<List<Object>> full = JniFiles.readColumn(file, "v", 0, ROWS, null);
        List<List<Object>> range = JniFiles.readColumn(file, "v", 333, 1_777, null);
        List<List<Object>> filtered = JniFiles.readColumn(file, "v", 0, ROWS, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column("id"), Expression.literal(THRESHOLD)));

        // Then every list is whole and in its own row (vortex-jni may order partitions differently)
        assertThat(full).containsExactlyInAnyOrderElementsOf(expected(element, size, 0, ROWS));
        assertThat(range).containsExactlyInAnyOrderElementsOf(expected(element, size, 333, 1_777));
        assertThat(filtered).containsExactlyInAnyOrderElementsOf(expected(element, size, (int) THRESHOLD, ROWS));
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(io.github.dfa1.vortex.inspect.VortexInspector.inspect(reader))
                    .as("the file must hold the encoding under test, not a fallback")
                    .contains("vortex.fixed_size_list");
        }
    }

    @ParameterizedTest(name = "{0} x {1}")
    @MethodSource("jniShapes")
    void jniWriter_javaReader_fullAndFiltered(Element element, int size, @TempDir Path tmp) throws IOException {
        // Given Rust writes the lists in three batches and picks whatever encoding it likes
        Path file = tmp.resolve("jni_fsl.vtx");
        writeWithJni(file, element, size);

        // When Java reads everything, then only what a filter on the id lets through
        List<Object[]> full = readJava(file, element, size, null);
        List<Object[]> filtered = readJava(file, element, size, ScanFilter.gte(ID, THRESHOLD));

        // Then each row's list is whole and belongs to its id
        assertThat(full).hasSize(ROWS);
        full.forEach(row -> assertThat((List<?>) row[1]).isEqualTo(list(element, size, (Long) row[0])));
        assertThat(full.stream().map(row -> (Long) row[0]).toList())
                .containsExactlyElementsOf(java.util.stream.LongStream.range(0, ROWS).boxed().toList());
        // pruning may keep rows below the threshold, but must keep every row at or above it
        assertThat(filtered.stream().map(row -> (Long) row[0]).filter(id -> id >= THRESHOLD).toList())
                .containsExactlyElementsOf(java.util.stream.LongStream.range(THRESHOLD, ROWS).boxed().toList());
        filtered.forEach(row -> assertThat((List<?>) row[1]).isEqualTo(list(element, size, (Long) row[0])));
    }

    /// Which lists of a nullable column are null.
    enum Nulls {
        NONE_NULL, EVERY_SEVENTH, ALL_NULL;

        boolean isNull(long row) {
            return switch (this) {
                case NONE_NULL -> false;
                case EVERY_SEVENTH -> row % 7 == 0;
                case ALL_NULL -> true;
            };
        }
    }

    /// Which elements inside the lists are null, by position in the flat elements array. The
    /// element type is non-nullable for `NOT_NULLABLE` and nullable for the others, so `NONE_NULL` is
    /// the nullable type that holds no null (Rust still writes a validity for it, or a constant one).
    enum ElementNulls {
        NOT_NULLABLE, NONE_NULL, EVERY_THIRD, ALL_NULL;

        boolean nullableType() {
            return this != NOT_NULLABLE;
        }

        boolean isNull(long flatIndex) {
            return switch (this) {
                case NOT_NULLABLE, NONE_NULL -> false;
                case EVERY_THIRD -> flatIndex % 3 == 0;
                case ALL_NULL -> true;
            };
        }
    }

    /// A nullable list column is the case the plain tests never reach: list size 0 (no elements at
    /// all), 1 (a list is one element), and all, some or none of the lists null. The elements of a
    /// null list are still in the flat array, as zeros.
    static Stream<Arguments> nullableShapes() {
        List<Arguments> cases = new ArrayList<>();
        for (Element element : Element.values()) {
            for (int size : new int[]{0, 1, 3}) {
                for (Nulls nulls : Nulls.values()) {
                    cases.add(Arguments.of(element, size, nulls));
                }
            }
        }
        return cases.stream();
    }

    /// Row counts around empty: no row, one row, two rows, with and without nulls allowed.
    static Stream<Arguments> tinyShapes() {
        List<Arguments> cases = new ArrayList<>();
        for (int rows : new int[]{0, 1, 2}) {
            for (int size : new int[]{0, 1, 3}) {
                cases.add(Arguments.of(rows, size, false, Nulls.NONE_NULL));
                cases.add(Arguments.of(rows, size, true, Nulls.NONE_NULL));
                cases.add(Arguments.of(rows, size, true, Nulls.ALL_NULL));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} x {1}, {2}")
    @MethodSource("nullableShapes")
    void javaWriter_jniReader_nullableLists(Element element, int size, Nulls nulls, @TempDir Path tmp)
            throws IOException {
        // Given
        Path file = tmp.resolve("java_fsl_nullable.vtx");
        writeWithJava(file, element, size, true, nulls, CHUNKS, CHUNK_ROWS);

        // When
        List<List<Object>> full = JniFiles.readColumn(file, "v", 0, ROWS, null);
        List<List<Object>> range = JniFiles.readColumn(file, "v", 333, 1_777, null);
        List<List<Object>> filtered = JniFiles.readColumn(file, "v", 0, ROWS, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column("id"), Expression.literal(THRESHOLD)));

        // Then null lists stay null, and the others keep their elements
        assertThat(full).containsExactlyInAnyOrderElementsOf(expectedRows(element, size, nulls, 0, ROWS));
        assertThat(range).containsExactlyInAnyOrderElementsOf(expectedRows(element, size, nulls, 333, 1_777));
        assertThat(filtered).containsExactlyInAnyOrderElementsOf(
                expectedRows(element, size, nulls, (int) THRESHOLD, ROWS));
    }

    @ParameterizedTest(name = "{0} x {1}, {2}")
    @MethodSource("nullableShapes")
    void jniWriter_javaReader_nullableLists(Element element, int size, Nulls nulls, @TempDir Path tmp)
            throws IOException {
        // Given
        Path file = tmp.resolve("jni_fsl_nullable.vtx");
        writeWithJni(file, element, size, true, nulls, CHUNKS, CHUNK_ROWS);

        // When
        List<Object[]> result = readJava(file, element, size, null);

        // Then
        assertJavaRows(result, element, size, nulls, ROWS);
    }

    @ParameterizedTest(name = "{0} rows, size {1}, nullable {2}, {3}")
    @MethodSource("tinyShapes")
    void javaWriter_jniReader_emptyAndTinyFiles(int rows, int size, boolean nullable, Nulls nulls,
                                               @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_fsl_tiny.vtx");
        writeWithJava(file, Element.INT, size, nullable, nulls, 1, rows);

        // When
        List<List<Object>> result = JniFiles.readColumn(file, "v", 0, rows, null);

        // Then
        assertThat(result).containsExactlyInAnyOrderElementsOf(expectedRows(Element.INT, size, nulls, 0, rows));
    }

    @ParameterizedTest(name = "{0} rows, size {1}, nullable {2}, {3}")
    @MethodSource("tinyShapes")
    void jniWriter_javaReader_emptyAndTinyFiles(int rows, int size, boolean nullable, Nulls nulls,
                                               @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("jni_fsl_tiny.vtx");
        writeWithJni(file, Element.INT, size, nullable, nulls, 1, rows);

        // When
        List<Object[]> result = readJava(file, Element.INT, size, null);

        // Then
        assertJavaRows(result, Element.INT, size, nulls, rows);
    }

    /// Elements that are themselves nullable, inside nullable and non-nullable lists: the elements
    /// child then carries its own validity, nested under the list's.
    static Stream<Arguments> nullableElementShapes() {
        List<Arguments> cases = new ArrayList<>();
        for (Element element : Element.values()) {
            for (int size : new int[]{1, 3}) {
                for (ElementNulls elementNulls : new ElementNulls[]{
                        ElementNulls.NONE_NULL, ElementNulls.EVERY_THIRD, ElementNulls.ALL_NULL}) {
                    cases.add(Arguments.of(element, size, false, Nulls.NONE_NULL, elementNulls));
                    cases.add(Arguments.of(element, size, true, Nulls.NONE_NULL, elementNulls));
                    cases.add(Arguments.of(element, size, true, Nulls.EVERY_SEVENTH, elementNulls));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} x {1}, nullable lists {2}, {3}, elements {4}")
    @MethodSource("nullableElementShapes")
    void javaWriter_jniReader_nullableElements(Element element, int size, boolean nullable, Nulls nulls,
                                              ElementNulls elementNulls, @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_fsl_nullable_elements.vtx");
        writeWithJava(file, element, size, nullable, nulls, elementNulls, CHUNKS, CHUNK_ROWS);

        // When
        List<List<Object>> full = JniFiles.readColumn(file, "v", 0, ROWS, null);
        List<List<Object>> range = JniFiles.readColumn(file, "v", 333, 1_777, null);
        List<List<Object>> filtered = JniFiles.readColumn(file, "v", 0, ROWS, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column("id"), Expression.literal(THRESHOLD)));

        // Then every null element is null, and no other element changed
        assertThat(full).containsExactlyInAnyOrderElementsOf(
                expectedRows(element, size, nulls, elementNulls, 0, ROWS));
        assertThat(range).containsExactlyInAnyOrderElementsOf(
                expectedRows(element, size, nulls, elementNulls, 333, 1_777));
        assertThat(filtered).containsExactlyInAnyOrderElementsOf(
                expectedRows(element, size, nulls, elementNulls, (int) THRESHOLD, ROWS));
    }

    @ParameterizedTest(name = "{0} x {1}, nullable lists {2}, {3}, elements {4}")
    @MethodSource("nullableElementShapes")
    void jniWriter_javaReader_nullableElements(Element element, int size, boolean nullable, Nulls nulls,
                                              ElementNulls elementNulls, @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("jni_fsl_nullable_elements.vtx");
        writeWithJni(file, element, size, nullable, nulls, elementNulls, CHUNKS, CHUNK_ROWS);

        // When
        List<Object[]> result = readJava(file, element, size, null);

        // Then
        assertJavaRows(result, element, size, nulls, elementNulls, ROWS);
    }

    private static void assertJavaRows(List<Object[]> rows, Element element, int size, Nulls nulls, int total) {
        assertJavaRows(rows, element, size, nulls, ElementNulls.NOT_NULLABLE, total);
    }

    private static void assertJavaRows(List<Object[]> rows, Element element, int size, Nulls nulls,
                                       ElementNulls elementNulls, int total) {
        assertThat(rows.stream().map(row -> (Long) row[0]).toList())
                .containsExactlyElementsOf(java.util.stream.LongStream.range(0, total).boxed().toList());
        rows.forEach(row -> assertThat(row[1]).as("row %s", row[0])
                .isEqualTo(nulls.isNull((Long) row[0]) ? null : list(element, size, (Long) row[0], elementNulls)));
    }

    private static List<List<Object>> expectedRows(Element element, int size, Nulls nulls, int from, int to) {
        return expectedRows(element, size, nulls, ElementNulls.NOT_NULLABLE, from, to);
    }

    private static List<List<Object>> expectedRows(Element element, int size, Nulls nulls,
                                                   ElementNulls elementNulls, int from, int to) {
        List<List<Object>> rows = new ArrayList<>();
        for (int row = from; row < to; row++) {
            rows.add(nulls.isNull(row) ? null : list(element, size, row, elementNulls));
        }
        return rows;
    }

    private static void writeWithJava(Path file, Element element, int size, boolean nullable, Nulls nulls,
                                      int chunks, int chunkRows) throws IOException {
        writeWithJava(file, element, size, nullable, nulls, ElementNulls.NOT_NULLABLE, chunks, chunkRows);
    }

    private static void writeWithJava(Path file, Element element, int size, boolean nullable, Nulls nulls,
                                      ElementNulls elementNulls, int chunks, int chunkRows) throws IOException {
        DType elementType = elementNulls.nullableType() ? element.dtype.asNullable() : element.dtype;
        DType.Struct schema = new DType.Struct(List.of(ID, LISTS),
                List.of(DType.I64, new DType.FixedSizeList(elementType, size, nullable)), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            for (int chunk = 0; chunk < chunks; chunk++) {
                long first = (long) chunk * chunkRows;
                long[] ids = new long[chunkRows];
                boolean[] validity = new boolean[chunkRows];
                Object flat = element.flat(first, chunkRows, size);
                for (int i = 0; i < chunkRows; i++) {
                    ids[i] = first + i;
                    validity[i] = !nulls.isNull(first + i);
                    for (int k = 0; !validity[i] && k < size; k++) {
                        java.lang.reflect.Array.set(flat, i * size + k, element.zero());
                    }
                }
                if (elementNulls.nullableType()) {
                    boolean[] elementValidity = new boolean[chunkRows * size];
                    for (int i = 0; i < elementValidity.length; i++) {
                        // a null list's slots are zeros, valid: its elements are never looked at
                        boolean inNullList = nulls.isNull(first + i / size);
                        elementValidity[i] = inNullList || !elementNulls.isNull((first * size) + i);
                        if (!elementValidity[i]) {
                            java.lang.reflect.Array.set(flat, i, element.zero());
                        }
                    }
                    flat = new NullableData(flat, elementValidity);
                }
                Object lists = new FixedSizeListData(flat, chunkRows);
                sut.writeChunk(Map.of(ID, ids, LISTS, nullable ? new NullableData(lists, validity) : lists));
            }
        }
    }

    static Stream<Arguments> jniShapes() {
        List<Arguments> cases = new ArrayList<>();
        for (Element element : Element.values()) {
            for (int size : new int[]{1, 3, 16}) {
                cases.add(Arguments.of(element, size));
            }
        }
        return cases.stream();
    }

    private static DType.Struct schema(Element element, int size) {
        return new DType.Struct(List.of(ID, LISTS),
                List.of(DType.I64, new DType.FixedSizeList(element.dtype, size, false)), false);
    }

    private static List<Object> list(Element element, int size, long row) {
        return list(element, size, row, ElementNulls.NOT_NULLABLE);
    }

    private static List<Object> list(Element element, int size, long row, ElementNulls elementNulls) {
        List<Object> list = new ArrayList<>(size);
        for (int k = 0; k < size; k++) {
            list.add(elementNulls.isNull(row * size + k) ? null : element.value(row, k));
        }
        return list;
    }

    private static List<List<Object>> expected(Element element, int size, int from, int to) {
        List<List<Object>> rows = new ArrayList<>();
        for (int row = from; row < to; row++) {
            rows.add(list(element, size, row));
        }
        return rows;
    }

    private static void writeWithJni(Path file, Element element, int size) throws IOException {
        writeWithJni(file, element, size, false, Nulls.NONE_NULL, CHUNKS, CHUNK_ROWS);
    }

    private static void writeWithJni(Path file, Element element, int size, boolean nullable, Nulls nulls,
                                     int chunks, int chunkRows) throws IOException {
        writeWithJni(file, element, size, nullable, nulls, ElementNulls.NOT_NULLABLE, chunks, chunkRows);
    }

    private static void writeWithJni(Path file, Element element, int size, boolean nullable, Nulls nulls,
                                     ElementNulls elementNulls, int chunks, int chunkRows) throws IOException {
        FieldType itemType = elementNulls.nullableType() ? FieldType.nullable(element.arrowType)
                : FieldType.notNullable(element.arrowType);
        FieldType listType = nullable ? FieldType.nullable(new ArrowType.FixedSizeList(size))
                : FieldType.notNullable(new ArrowType.FixedSizeList(size));
        Schema schema = new Schema(List.of(
                Field.notNullable("id", new ArrowType.Int(64, true)),
                new Field("v", listType,
                        List.of(new Field("item", itemType, null)))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int chunk = 0; chunk < chunks; chunk++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    FixedSizeListVector lists = (FixedSizeListVector) root.getVector("v");
                    ids.allocateNew(chunkRows);
                    lists.allocateNew();
                    FieldVector items = lists.getDataVector();
                    for (int i = 0; i < chunkRows; i++) {
                        long row = (long) chunk * chunkRows + i;
                        ids.setSafe(i, row);
                        boolean isNull = nulls.isNull(row);
                        if (isNull) {
                            lists.setNull(i);
                        } else {
                            lists.setNotNull(i);
                        }
                        for (int k = 0; k < size; k++) {
                            Object value = isNull ? element.zero()
                                    : elementNulls.isNull(row * size + k) ? null : element.value(row, k);
                            element.write(items, i * size + k, value);
                        }
                    }
                    root.setRowCount(chunkRows);
                    JniFiles.writeBatch(ALLOCATOR, writer, root);
                }
            }
        }
    }

    /// Each row as `{id, list}`.
    private static List<Object[]> readJava(Path file, Element element, int size, ScanFilter filter)
            throws IOException {
        io.github.dfa1.vortex.reader.ScanOptions options = io.github.dfa1.vortex.reader.ScanOptions.all();
        if (filter != null) {
            options = options.withFilter(filter);
        }
        List<Object[]> rows = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
             Arena arena = Arena.ofConfined()) {
            reader.scan(options).forEachRemaining(chunk -> {
                Array idColumn = chunk.column(ID);
                MaskedArray.Unwrapped unwrapped = MaskedArray.unwrap(chunk.column(LISTS));
                FixedSizeListArray listColumn = (FixedSizeListArray) unwrapped.inner();
                MemorySegment ids = idColumn.materialize(arena);
                MaskedArray.Unwrapped unwrappedElements = MaskedArray.unwrap(listColumn.elements());
                // Rust writes a column whose elements are all null as a null array, which has no values
                boolean allNull = unwrappedElements.inner() instanceof NullArray;
                Array inner = unwrappedElements.inner();
                MemorySegment elements = allNull || !element.lanes() ? null : inner.materialize(arena);
                assertThat(listColumn.fixedSize()).isEqualTo(size);
                for (long row = 0; row < listColumn.length(); row++) {
                    if (unwrapped.validity() != null && !unwrapped.validity().getBoolean(row)) {
                        rows.add(new Object[]{ids.getAtIndex(VortexFormat.LE_LONG, row), null});
                        continue;
                    }
                    List<Object> list = new ArrayList<>(size);
                    for (int k = 0; k < size; k++) {
                        long flat = row * size + k;
                        boolean elementIsNull = allNull || unwrappedElements.validity() != null
                                && !unwrappedElements.validity().getBoolean(flat);
                        list.add(elementIsNull ? null : element.get(inner, elements, flat));
                    }
                    rows.add(new Object[]{ids.getAtIndex(VortexFormat.LE_LONG, row), list});
                }
            });
        }
        return rows;
    }
}
