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
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MapArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.ListViewData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.complex.impl.UnionMapWriter;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Cross-language ground truth for `vortex.map` (issue #351), in both directions.
///
/// No `map.vortex` S3 compat fixture is published (checked through v0.84.0), so vortex-jni is used
/// directly as the oracle: Java writes and Rust reads, then Rust writes and Java reads. The column
/// shape is `map<utf8, i64?>` — non-nullable string keys, nullable long values — plus a nullable
/// map column, because a null map row is represented by a null `entries` row rather than by
/// anything on the map node itself.
class MapEncodingInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();

    private static final DType.Map MAP_UTF8_I64 =
            new DType.Map(DType.UTF8, DType.I64.asNullable(), false, false);

    private static final DType.Struct JAVA_SCHEMA = new DType.Struct(
            List.of(ColumnName.of("attrs")), List.of(MAP_UTF8_I64), false);

    private static final DType.Struct JAVA_NULLABLE_SCHEMA = new DType.Struct(
            List.of(ColumnName.of("attrs")), List.of(MAP_UTF8_I64.asNullable()), false);

    static {
        NativeLoader.loadJni();
    }

    private static Schema arrowMapSchema(boolean nullableMap, boolean keysSorted) {
        Field key = new Field("key", FieldType.notNullable(new ArrowType.Utf8()), null);
        Field value = new Field("value", FieldType.nullable(new ArrowType.Int(64, true)), null);
        Field entries = new Field(MapVector.DATA_VECTOR_NAME,
                FieldType.notNullable(new ArrowType.Struct()), List.of(key, value));
        FieldType mapType = new FieldType(nullableMap, new ArrowType.Map(keysSorted), null);
        return new Schema(List.of(new Field("attrs", mapType, List.of(entries))));
    }

    @Test
    void javaWriter_rustReader_map(@TempDir Path tmp) throws IOException {
        // Given three rows — {a:1, b:2}, {}, {c:null} — the empty row pins that a zero-length
        // entry list survives, and the null value pins the entries struct's own validity child
        Path file = tmp.resolve("java_map.vtx");
        ListViewData data = entries(
                new String[]{"a", "b", "c"},
                new long[]{1L, 2L, 0L},
                new boolean[]{true, true, false},
                new int[]{0, 2, 2}, new int[]{2, 0, 1}, 3L);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = io.github.dfa1.vortex.writer.VortexWriter.create(
                     ch, JAVA_SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of(ColumnName.of("attrs"), data));
        }

        // Then — the Rust reader must reconstruct exactly those three rows
        List<Map<String, Long>> result = readMapColumnWithJni(file);
        assertThat(result).containsExactly(
                orderedMap("a", 1L, "b", 2L),
                Map.of(),
                singletonNullValue("c"));
    }

    @Test
    void javaWriter_rustReader_nullableMap(@TempDir Path tmp) throws IOException {
        // Given a nullable map column whose middle row is null — validity is delegated to the
        // entries child, so this exercises the list-view's own fourth (validity) child slot;
        // there is no vortex.masked node anywhere under the map on the wire, which is exactly
        // what the Rust reader demands of a map's entries child
        Path file = tmp.resolve("java_map_nullable.vtx");
        ListViewData values = entries(
                new String[]{"x", "y"},
                new long[]{7L, 8L},
                new boolean[]{true, true},
                new int[]{0, 1, 1}, new int[]{1, 0, 1}, 3L);
        NullableData data = new NullableData(values, new boolean[]{true, false, true});
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = io.github.dfa1.vortex.writer.VortexWriter.create(
                     ch, JAVA_NULLABLE_SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of(ColumnName.of("attrs"), data));
        }

        // Then
        List<Map<String, Long>> result = readMapColumnWithJni(file);
        assertThat(result).containsExactly(Map.of("x", 7L), null, Map.of("y", 8L));
    }

    @Test
    void rustWriter_javaReader_map(@TempDir Path tmp) throws IOException {
        // Given a Rust-written file whose Arrow map column carries the same three rows
        Path file = tmp.resolve("rust_map.vtx");
        writeMapColumnWithJni(file, false, List.of(
                orderedMap("a", 1L, "b", 2L),
                Map.of(),
                singletonNullValue("c")));

        // When
        List<Map<String, Long>> result = readMapColumnWithJava(file);

        // Then
        assertThat(result).containsExactly(
                orderedMap("a", 1L, "b", 2L),
                Map.of(),
                singletonNullValue("c"));
    }

    @Test
    void rustWriter_javaReader_nullableMap(@TempDir Path tmp) throws IOException {
        // Given a Rust-written nullable map column with a null row in the middle
        Path file = tmp.resolve("rust_map_nullable.vtx");
        List<Map<String, Long>> rows = new ArrayList<>();
        rows.add(Map.of("x", 7L));
        rows.add(null);
        rows.add(Map.of("y", 8L));
        writeMapColumnWithJni(file, true, rows);

        // When
        List<Map<String, Long>> result = readMapColumnWithJava(file);

        // Then
        assertThat(result).containsExactly(Map.of("x", 7L), null, Map.of("y", 8L));
    }

    @Test
    void rustWriter_javaReader_mapDtypeSurvivesThePostscript(@TempDir Path tmp) throws IOException {
        // Given a Rust-written map file — the DType blob is the only place the map's key/value
        // types and keys_sorted flag live, so parsing it back is a wire-format boundary of its own
        Path file = tmp.resolve("rust_map_schema.vtx");
        writeMapColumnWithJni(file, false, List.of(Map.of("k", 1L)));

        // When
        DType result;
        try (var reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = ((DType.Struct) reader.dtype()).field("attrs");
        }

        // Then
        assertThat(result).isInstanceOf(DType.Map.class);
        DType.Map mapDtype = (DType.Map) result;
        assertThat(mapDtype.keyType()).isEqualTo(DType.UTF8);
        assertThat(mapDtype.keyType().nullable()).isFalse();
        assertThat(mapDtype.valueType()).isEqualTo(DType.I64.asNullable());
        assertThat(mapDtype.nullable()).isFalse();
        assertThat(mapDtype.keysSorted()).isFalse();
    }

    @Test
    void rustWriter_javaReader_keysSortedSurvivesThePostscript(@TempDir Path tmp) throws IOException {
        // Given a Rust-written file whose map declares keys_sorted. The flag is a pure producer
        // assertion — nothing in the data reflects it — so the DType blob is the only place it
        // can survive, and it is part of DType.Map's identity: dropping it would silently report
        // a different schema than the file declares
        Path file = tmp.resolve("rust_map_keys_sorted.vtx");
        writeMapColumnWithJni(file, false, true, List.of(Map.of("k", 1L)));

        // When
        DType result;
        try (var reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = ((DType.Struct) reader.dtype()).field("attrs");
        }

        // Then
        assertThat(((DType.Map) result).keysSorted()).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ListViewData entries(
            String[] keys, long[] values, boolean[] valueValidity, int[] offsets, int[] sizes, long rows
    ) {
        StructData entryStructs = new StructData(List.of(keys, new NullableData(values, valueValidity)));
        return new ListViewData(entryStructs, offsets, sizes, rows);
    }

    private static Map<String, Long> orderedMap(String k1, Long v1, String k2, Long v2) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static Map<String, Long> singletonNullValue(String key) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(key, null);
        return m;
    }

    private static void writeMapColumnWithJni(Path file, boolean nullableMap, List<Map<String, Long>> rows)
            throws IOException {
        writeMapColumnWithJni(file, nullableMap, false, rows);
    }

    private static void writeMapColumnWithJni(
            Path file, boolean nullableMap, boolean keysSorted, List<Map<String, Long>> rows
    ) throws IOException {
        Schema schema = arrowMapSchema(nullableMap, keysSorted);
        String uri = file.toAbsolutePath().toUri().toString();
        try (dev.vortex.api.VortexWriter writer =
                     dev.vortex.api.VortexWriter.builder(SESSION, uri, schema, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            MapVector vec = (MapVector) root.getVector("attrs");
            UnionMapWriter mapWriter = vec.getWriter();
            mapWriter.allocate();
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Long> row = rows.get(i);
                mapWriter.setPosition(i);
                if (row == null) {
                    mapWriter.writeNull();
                    continue;
                }
                mapWriter.startMap();
                for (Map.Entry<String, Long> e : row.entrySet()) {
                    mapWriter.startEntry();
                    mapWriter.key().varChar().writeVarChar(e.getKey());
                    if (e.getValue() != null) {
                        mapWriter.value().bigInt().writeBigInt(e.getValue());
                    }
                    mapWriter.endEntry();
                }
                mapWriter.endMap();
            }
            mapWriter.setValueCount(rows.size());
            root.setRowCount(rows.size());
            try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, arrowSchema);
                writer.writeBatch(arr.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    private static List<Map<String, Long>> readMapColumnWithJni(Path file) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        ScanOptions opts = ScanOptions.builder()
                                   .projection(Expression.select(new String[]{"attrs"}, Expression.root()))
                                   .build();
        List<Map<String, Long>> rows = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    MapVector vec = (MapVector) root.getVector("attrs");
                    StructVector entryVec = (StructVector) vec.getDataVector();
                    // Read the entry children through ValueVector#getObject: the Rust reader hands
                    // back utf8 keys as a ViewVarCharVector, not the VarCharVector the written
                    // schema declared, so a concrete cast here would be a false failure.
                    FieldVector keyVec = entryVec.getChild("key");
                    FieldVector valueVec = entryVec.getChild("value");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        if (vec.isNull(i)) {
                            rows.add(null);
                            continue;
                        }
                        int start = vec.getOffsetBuffer().getInt((long) i * MapVector.OFFSET_WIDTH);
                        int end = vec.getOffsetBuffer().getInt((long) (i + 1) * MapVector.OFFSET_WIDTH);
                        Map<String, Long> row = new LinkedHashMap<>();
                        for (int j = start; j < end; j++) {
                            row.put(String.valueOf(keyVec.getObject(j)), (Long) valueVec.getObject(j));
                        }
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }

    private static List<Map<String, Long>> readMapColumnWithJava(Path file) throws IOException {
        List<Map<String, Long>> rows = new ArrayList<>();
        try (var reader = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = reader.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("attrs"))) {
            while (iter.hasNext()) {
                var chunk = iter.next();
                MapArray map = chunk.column("attrs");
                MaskedArray masked = map.entries() instanceof MaskedArray m ? m : null;
                Array inner = masked != null ? masked.inner() : map.entries();
                ListViewArray entries = (ListViewArray) inner;
                Array offsets = entries.offsets();
                Array sizes = entries.sizes();
                StructArray entryStructs = (StructArray) entries.elements();
                VarBinArray keys = (VarBinArray) entryStructs.field(0);
                Array valuesField = entryStructs.field(1);
                MaskedArray valueMask = valuesField instanceof MaskedArray m ? m : null;
                LongArray values = (LongArray) (valueMask != null ? valueMask.inner() : valuesField);
                for (long i = 0; i < map.length(); i++) {
                    if (masked != null && !masked.isValid(i)) {
                        rows.add(null);
                        continue;
                    }
                    Map<String, Long> row = new LinkedHashMap<>();
                    long start = indexAt(offsets, i);
                    long size = indexAt(sizes, i);
                    for (long j = start; j < start + size; j++) {
                        row.put(new String(keys.getBytes(j), StandardCharsets.UTF_8),
                                valueMask != null && !valueMask.isValid(j) ? null : values.getLong(j));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /// Reads one list-view offset or size. The Rust writer picks the narrowest integer width that
    /// fits, so these children come back as any of the primitive array widths, not just `IntArray`.
    private static long indexAt(Array array, long i) {
        return switch (array) {
            case ByteArray a -> a.getInt(i);
            case ShortArray a -> a.getInt(i);
            case IntArray a -> a.getInt(i);
            case LongArray a -> a.getLong(i);
            default -> throw new IllegalStateException(
                    "unexpected list-view offsets/sizes array: " + array.getClass().getSimpleName());
        };
    }
}
