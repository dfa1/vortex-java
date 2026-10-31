package io.github.dfa1.vortex.integration;

import dev.vortex.api.Expression;
import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.Element;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.ElementNulls;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.Nulls;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.writer.ColumnEncoding;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.ListViewData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListViewVector;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// `vortex.listview` against vortex-jni, with the corner cases of [ListInteropIntegrationTest]:
/// null lists, nullable elements, every element type, lengths 0 to 3. A list view stores an offset
/// and a size per row instead of `n + 1` offsets, so the lists need not be contiguous or in row
/// order: the Java writer is also run with the rows' elements laid out in reverse, which a
/// decoder that assumed `offset[i + 1] = offset[i] + size[i]` would read wrongly.
class ListViewInteropIntegrationTest {

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

    static Stream<Arguments> javaShapes() {
        return shapes().flatMap(a -> Stream.of(false, true)
                .map(reversed -> Arguments.of(a.get()[0], a.get()[1], a.get()[2], reversed)));
    }

    @ParameterizedTest(name = "{0}, lists {1}, elements {2}, reversed {3}")
    @MethodSource("javaShapes")
    void javaWriter_jniReader_rowRangeAndFiltered(Element element, Nulls nulls, ElementNulls elementNulls,
                                                 boolean reversed, @TempDir Path tmp) throws IOException {
        // Given the list view encoding forced, so the file holds it whatever the cascade would pick
        Path file = tmp.resolve("java_listview.vtx");
        writeWithJava(file, element, nulls, elementNulls, reversed);

        // When
        List<List<Object>> full = JniFiles.readColumn(file, "v", 0, ROWS, null);
        List<List<Object>> range = JniFiles.readColumn(file, "v", 333, 1_777, null);
        List<List<Object>> filtered = JniFiles.readColumn(file, "v", 0, ROWS, Expression.binary(
                Expression.BinaryOp.GTE, Expression.column("id"), Expression.literal(THRESHOLD)));

        // Then
        assertThat(full).containsExactlyInAnyOrderElementsOf(
                ListInteropIntegrationTest.expected(element, nulls, elementNulls, 0, ROWS));
        assertThat(range).containsExactlyInAnyOrderElementsOf(
                ListInteropIntegrationTest.expected(element, nulls, elementNulls, 333, 1_777));
        assertThat(filtered).containsExactlyInAnyOrderElementsOf(
                ListInteropIntegrationTest.expected(element, nulls, elementNulls, (int) THRESHOLD, ROWS));
        // an all-null column is one null constant before any encoding is tried, as Rust's compressor
        // makes it, so only the other shapes hold the list view
        String encoding = "vortex.listview";
        if (nulls == Nulls.ALL_NULL) {
            encoding = "vortex.constant";
        }
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(VortexInspector.inspect(reader)).as("the file must hold the encoding under test")
                    .contains(encoding);
        }
    }

    @ParameterizedTest(name = "{0}, lists {1}, elements {2}")
    @MethodSource("shapes")
    void jniWriter_javaReader_everyRow(Element element, Nulls nulls, ElementNulls elementNulls, @TempDir Path tmp)
            throws IOException {
        // Given Arrow's ListView type, which vortex-jni writes as vortex.listview
        Path file = tmp.resolve("jni_listview.vtx");
        writeWithJni(file, element, nulls, elementNulls);

        // When
        List<List<Object>> result = readJava(file, element);

        // Then
        assertThat(result).containsExactlyElementsOf(
                ListInteropIntegrationTest.expected(element, nulls, elementNulls, 0, ROWS));
    }

    private static void writeWithJava(Path file, Element element, Nulls nulls, ElementNulls elementNulls,
                                      boolean reversed) throws IOException {
        boolean nullableLists = nulls != Nulls.NONE_NULL;
        DType elementType = elementNulls.nullableType() ? element.dtype.asNullable() : element.dtype;
        DType.Struct schema = new DType.Struct(List.of(ID, LISTS),
                List.of(DType.I64, new DType.List(elementType, nullableLists)), false);
        WriteOptions options = WriteOptions.defaults().withoutEditions()
                .withColumnEncoding(LISTS, ColumnEncoding.candidates(EncodingId.VORTEX_LISTVIEW));
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, options)) {
            long flat = 0;
            for (int chunk = 0; chunk < CHUNKS; chunk++) {
                long first = (long) chunk * CHUNK_ROWS;
                long[] ids = new long[CHUNK_ROWS];
                boolean[] listValidity = new boolean[CHUNK_ROWS];
                List<List<Object>> perRow = new ArrayList<>();
                for (int i = 0; i < CHUNK_ROWS; i++) {
                    long row = first + i;
                    ids[i] = row;
                    listValidity[i] = !nulls.isNull(row);
                    List<Object> values = new ArrayList<>();
                    int length = listValidity[i] ? ListInteropIntegrationTest.length(row) : 0;
                    for (int k = 0; k < length; k++) {
                        values.add(elementNulls.isNull(flat + k) ? null : element.value(row, k));
                    }
                    flat += length;
                    perRow.add(values);
                }
                // rows' elements back to back, in row order or in reverse row order
                int[] offsets = new int[CHUNK_ROWS];
                int[] sizes = new int[CHUNK_ROWS];
                List<Object> values = new ArrayList<>();
                for (int n = 0; n < CHUNK_ROWS; n++) {
                    int i = reversed ? CHUNK_ROWS - 1 - n : n;
                    offsets[i] = values.size();
                    sizes[i] = perRow.get(i).size();
                    values.addAll(perRow.get(i));
                }
                boolean[] elementValidity = new boolean[values.size()];
                for (int i = 0; i < elementValidity.length; i++) {
                    elementValidity[i] = values.get(i) != null;
                }
                Object packed = element.pack(values);
                Object elements = elementNulls.nullableType() ? new NullableData(packed, elementValidity) : packed;
                Object lists = new ListViewData(elements, offsets, sizes, CHUNK_ROWS);
                sut.writeChunk(Map.of(ID, ids, LISTS, nullableLists ? new NullableData(lists, listValidity) : lists));
            }
        }
    }

    private static void writeWithJni(Path file, Element element, Nulls nulls, ElementNulls elementNulls)
            throws IOException {
        FieldType itemType = elementNulls.nullableType() ? FieldType.nullable(element.arrowType)
                : FieldType.notNullable(element.arrowType);
        FieldType listType = nulls != Nulls.NONE_NULL ? FieldType.nullable(ArrowType.ListView.INSTANCE)
                : FieldType.notNullable(ArrowType.ListView.INSTANCE);
        Schema schema = new Schema(List.of(
                Field.notNullable("id", new ArrowType.Int(64, true)),
                new Field("v", listType, List.of(new Field("item", itemType, null)))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            long flat = 0;
            for (int chunk = 0; chunk < CHUNKS; chunk++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    ListViewVector lists = (ListViewVector) root.getVector("v");
                    ids.allocateNew(CHUNK_ROWS);
                    lists.allocateNew();
                    FieldVector items = lists.getDataVector();
                    for (int i = 0; i < CHUNK_ROWS; i++) {
                        long row = (long) chunk * CHUNK_ROWS + i;
                        ids.setSafe(i, row);
                        if (nulls.isNull(row)) {
                            lists.setNull(i);
                            continue;
                        }
                        int start = lists.startNewValue(i);
                        int length = ListInteropIntegrationTest.length(row);
                        for (int k = 0; k < length; k++) {
                            element.write(items, start + k,
                                    elementNulls.isNull(flat + k) ? null : element.value(row, k));
                        }
                        flat += length;
                        lists.endValue(i, length);
                    }
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
                // Rust may canonicalize an Arrow ListView whose lists are in order to a plain
                // vortex.list, so both layouts are read: (offset, size) per row, or n + 1 offsets
                Array listArray = unwrapped.inner();
                Array elementArray;
                Array offsetArray;
                Array sizeArray;
                if (listArray instanceof ListViewArray view) {
                    elementArray = view.elements();
                    offsetArray = view.offsets();
                    sizeArray = view.sizes();
                } else {
                    ListArray list = (ListArray) listArray;
                    elementArray = list.elements();
                    offsetArray = list.offsets();
                    sizeArray = null;
                }
                MaskedArray.Unwrapped unwrappedElements = MaskedArray.unwrap(elementArray);
                Array inner = unwrappedElements.inner();
                boolean allNull = inner instanceof NullArray;
                MemorySegment lanes = allNull || !element.lanes() ? null : inner.materialize(arena);
                MemorySegment offsets = offsetArray.materialize(arena);
                MemorySegment sizes = sizeArray == null ? null : sizeArray.materialize(arena);
                int offsetBytes = ((DType.Primitive) offsetArray.dtype()).ptype().byteSize();
                int sizeBytes = sizeArray == null ? 0 : ((DType.Primitive) sizeArray.dtype()).ptype().byteSize();
                for (long row = 0; row < listArray.length(); row++) {
                    if (unwrapped.validity() != null && !unwrapped.validity().getBoolean(row)) {
                        rows.add(null);
                        continue;
                    }
                    long from = ListInteropIntegrationTest.offset(offsets, offsetBytes, row);
                    long to = sizes == null ? ListInteropIntegrationTest.offset(offsets, offsetBytes, row + 1)
                            : from + ListInteropIntegrationTest.offset(sizes, sizeBytes, row);
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
}
