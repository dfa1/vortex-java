package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Which text and numeric columns get Rust's dict layout, and how many dictionaries it makes (#650,
/// #649). Rust's `DictStrategy` admits a column by its first 8192 rows and starts a new dictionary
/// whenever the open one reaches 65,535 entries or 1 MiB, so a column of any total cardinality can
/// keep its dict layout.
@Tag("slow")
class DictLayoutInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final int CHUNK = 65_536;

    static {
        NativeLoader.loadJni();
    }

    @ParameterizedTest(name = "pool of {0} values")
    @ValueSource(ints = {100, 3_000, 20_000, 150_000})
    void admitsTheColumnsRustAdmits(int poolSize, @TempDir Path tmp) throws Exception {
        // Given — 3 chunks drawing at random from a pool: the pool decides how distinct the first 8192 rows are
        Random r = new Random(11);
        String[] pool = new String[poolSize];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = "https://example.org/path/" + Long.toString(r.nextLong() & 0xffffffffffL, 36);
        }
        String[] values = new String[CHUNK * 3];
        for (int i = 0; i < values.length; i++) {
            values[i] = pool[r.nextInt(pool.length)];
        }

        // When
        Path java = writeJava(tmp, values);
        Path jni = writeJni(tmp, values);

        // Then
        assertThat(dictLayouts(EncodingTrees.of(java))).isEqualTo(dictLayouts(EncodingTrees.of(jni)));
    }

    @Test
    void startsANewDictionaryWhenTheOpenOneIsFull(@TempDir Path tmp) throws Exception {
        // Given — 8 chunks, each drawing 3000 values from a window that slides 2500 values on: well under
        // half distinct in the first 8192 rows, but 23000 values of 96 bytes overall, past 1 MiB
        Random r = new Random(5);
        int chunks = 8;
        String[] pool = new String[2500 * chunks + 3000];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = "https://example.org/some/long/path/segment/" + Long.toString(r.nextLong() & 0xffffffffffL, 36)
                    + "/index.html?utm=" + i;
        }
        String[] values = new String[CHUNK * chunks];
        for (int i = 0; i < values.length; i++) {
            values[i] = pool[(i / CHUNK) * 2500 + r.nextInt(3000)];
        }

        // When
        Path java = writeJava(tmp, values);
        Path jni = writeJni(tmp, values);

        // Then — Rust fills one dictionary and starts the next, and so do we
        assertThat(dictLayouts(EncodingTrees.of(jni))).isEqualTo(2);
        assertThat(dictLayouts(EncodingTrees.of(java))).isEqualTo(2);
        assertThat(readAll(java)).containsExactly(values);
    }

    @ParameterizedTest(name = "pool of {0} values")
    @ValueSource(ints = {100, 3_000, 20_000, 150_000})
    void admitsTheNumericColumnsRustAdmits(int poolSize, @TempDir Path tmp) throws Exception {
        // Given — 3 chunks of full-width i64 drawn at random from a pool (hashes, like ClickBench's URLHash)
        Random r = new Random(11);
        long[] pool = new long[poolSize];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = r.nextLong();
        }
        long[] values = new long[CHUNK * 3];
        for (int i = 0; i < values.length; i++) {
            values[i] = pool[r.nextInt(pool.length)];
        }

        // When
        Path java = writeJava(tmp, values);
        Path jni = writeJni(tmp, values);

        // Then
        assertThat(dictLayouts(EncodingTrees.of(java))).isEqualTo(dictLayouts(EncodingTrees.of(jni)));
    }

    @Test
    void startsANewNumericDictionaryWhenTheOpenOneIsFull(@TempDir Path tmp) throws Exception {
        // Given — 8 chunks: the first 8192 rows hold 2000 distinct values, every row after them is new
        Random r = new Random(5);
        long[] values = new long[CHUNK * 8];
        for (int i = 0; i < values.length; i++) {
            values[i] = i < 8_192 ? r.nextLong() % 2_000 * 1_000_003L : r.nextLong();
        }

        // When
        Path java = writeJava(tmp, values);
        Path jni = writeJni(tmp, values);

        // Then — whatever Rust decides for this column, so do we, dictionary for dictionary
        assertThat(dictLayouts(EncodingTrees.of(java))).isEqualTo(dictLayouts(EncodingTrees.of(jni)));
        assertThat(readAllLongs(java)).containsExactly(values);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16"})
    void admitsANullableNarrowColumnAsRustDoes(PType ptype, @TempDir Path tmp) throws Exception {
        // Given — the profile of Public BI Generico's `Disco` first 8192 rows (#651): a nullable
        // SMALLINT, 40% valid, 62 distinct values in 5..132 with a few far more common than the
        // rest, value runs of about 2 rows between null runs of about 12. Rust's dict layout admits
        // every primitive and writes it as one values pool plus run-end codes; Java filtered narrow
        // widths out before the probe and wrote a 1.44x larger bit-packed column with a validity
        // per chunk
        Random r = new Random(651);
        int[] pool = new int[62];
        for (int p = 0; p < pool.length; p++) {
            pool[p] = 5 + p * 2;
        }
        int n = CHUNK * 3;
        int[] values = new int[n];
        boolean[] valid = new boolean[n];
        int i = 0;
        while (i < n) {
            boolean isValid = r.nextInt(100) >= 22;
            int run = isValid ? 1 + r.nextInt(3) : 1 + r.nextInt(23);
            int value = pool[Math.min(r.nextInt(pool.length), r.nextInt(pool.length))];
            for (int j = 0; j < run && i < n; j++, i++) {
                values[i] = value;
                valid[i] = isValid;
            }
        }

        // When
        Path java = writeJavaNullable(tmp, ptype, values, valid);
        Path jni = writeJniNullable(tmp, ptype, values, valid);

        // Then — the same layouts and encodings, node for node, the same bytes (8-bit values have
        // U8 codes, as Rust's dict builder bounds the codes by the values' range: Java's U16 codes
        // had the same tree), and vortex-jni reads Java's file back to the same values as its own
        assertThat(EncodingTrees.of(java)).isEqualTo(EncodingTrees.of(jni));
        assertThat(EncodingTrees.flatBuffersHex(java)).isEqualTo(EncodingTrees.flatBuffersHex(jni));
        assertThat(dictLayouts(EncodingTrees.of(java))).isPositive();
        assertThat(readWithJni(java)).isEqualTo(readWithJni(jni));
    }

    private static Path writeJavaNullable(Path tmp, PType ptype, int[] values, boolean[] valid) throws IOException {
        Path file = tmp.resolve("java.vortex");
        ColumnName col = ColumnName.of("v");
        DType.Struct schema = DType.structBuilder().field(col, new DType.Primitive(ptype, true)).build();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            for (int from = 0; from < values.length; from += CHUNK) {
                Object chunk = switch (ptype) {
                    case I8, U8 -> {
                        byte[] a = new byte[CHUNK];
                        for (int i = 0; i < CHUNK; i++) {
                            a[i] = (byte) values[from + i];
                        }
                        yield a;
                    }
                    default -> {
                        short[] a = new short[CHUNK];
                        for (int i = 0; i < CHUNK; i++) {
                            a[i] = (short) values[from + i];
                        }
                        yield a;
                    }
                };
                w.writeChunk(Map.of(col, new NullableData(chunk, java.util.Arrays.copyOfRange(valid, from, from + CHUNK))));
            }
        }
        return file;
    }

    private static Path writeJniNullable(Path tmp, PType ptype, int[] values, boolean[] valid) throws Exception {
        Path file = tmp.resolve("jni.vortex");
        ArrowType type = switch (ptype) {
            case I8 -> new ArrowType.Int(8, true);
            case U8 -> new ArrowType.Int(8, false);
            case U16 -> new ArrowType.Int(16, false);
            default -> new ArrowType.Int(16, true);
        };
        Schema arrow = new Schema(List.of(Field.nullable("v", type)));
        try (VortexWriter w = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), arrow, ALLOCATOR).build()) {
            for (int from = 0; from < values.length; from += CHUNK) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(arrow, ALLOCATOR)) {
                    FieldVector v = root.getVector("v");
                    root.allocateNew();
                    for (int i = 0; i < CHUNK; i++) {
                        int at = from + i;
                        if (!valid[at]) {
                            v.setNull(i);
                            continue;
                        }
                        switch (v) {
                            case TinyIntVector t -> t.setSafe(i, values[at]);
                            case UInt1Vector u -> u.setSafe(i, values[at]);
                            case UInt2Vector u -> u.setSafe(i, values[at]);
                            case SmallIntVector s -> s.setSafe(i, values[at]);
                            default -> throw new IllegalStateException(v.getClass().getName());
                        }
                    }
                    root.setRowCount(CHUNK);
                    try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR); ArrowSchema as = ArrowSchema.allocateNew(ALLOCATOR)) {
                        Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, as);
                        w.writeBatch(array.memoryAddress(), as.memoryAddress());
                    }
                }
            }
        }
        return file;
    }

    /// Every row of column `v` as vortex-jni reads it, `null` for a null row.
    private static List<Object> readWithJni(Path file) throws Exception {
        List<Object> out = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(dev.vortex.api.ScanOptions.builder()
                .projection(Expression.select(new String[]{"v"}, Expression.root())).build());
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    FieldVector vec = reader.getVectorSchemaRoot().getVector("v");
                    for (int i = 0; i < vec.getValueCount(); i++) {
                        out.add(vec.getObject(i));
                    }
                }
            }
        }
        return out;
    }

    private static Path writeJava(Path tmp, long[] values) throws IOException {
        Path file = tmp.resolve("java.vortex");
        DType.Struct schema = new DType.Struct(List.of(ColumnName.of("url")), List.of(DTypes.I64), false);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            for (int from = 0; from < values.length; from += CHUNK) {
                w.writeChunk(Map.of(ColumnName.of("url"), java.util.Arrays.copyOfRange(values, from, from + CHUNK)));
            }
        }
        return file;
    }

    private static Path writeJni(Path tmp, long[] values) throws Exception {
        Path file = tmp.resolve("jni.vortex");
        Schema arrow = new Schema(List.of(Field.notNullable("url", new ArrowType.Int(64, true))));
        try (VortexWriter w = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), arrow, ALLOCATOR).build()) {
            for (int from = 0; from < values.length; from += CHUNK) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(arrow, ALLOCATOR)) {
                    org.apache.arrow.vector.BigIntVector v = (org.apache.arrow.vector.BigIntVector) root.getVector("url");
                    root.allocateNew();
                    for (int i = 0; i < CHUNK; i++) {
                        v.setSafe(i, values[from + i]);
                    }
                    root.setRowCount(CHUNK);
                    try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR); ArrowSchema as = ArrowSchema.allocateNew(ALLOCATOR)) {
                        Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, as);
                        w.writeBatch(array.memoryAddress(), as.memoryAddress());
                    }
                }
            }
        }
        return file;
    }

    private static long[] readAllLongs(Path file) throws IOException {
        List<Long> out = new ArrayList<>();
        try (var reader = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = reader.scan(ScanOptions.columns(ColumnName.of("url")))) {
            while (iter.hasNext()) {
                try (var chunk = iter.next()) {
                    var longs = (io.github.dfa1.vortex.reader.array.LongArray) chunk.column(ColumnName.of("url"));
                    for (long i = 0; i < longs.length(); i++) {
                        out.add(longs.getLong(i));
                    }
                }
            }
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    private static Path writeJava(Path tmp, String[] values) throws IOException {
        Path file = tmp.resolve("java.vortex");
        DType.Struct schema = new DType.Struct(List.of(ColumnName.of("url")), List.of(DTypes.UTF8), false);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            for (int from = 0; from < values.length; from += CHUNK) {
                String[] chunk = new String[CHUNK];
                System.arraycopy(values, from, chunk, 0, CHUNK);
                w.writeChunk(Map.of(ColumnName.of("url"), chunk));
            }
        }
        return file;
    }

    private static Path writeJni(Path tmp, String[] values) throws Exception {
        Path file = tmp.resolve("jni.vortex");
        Schema arrow = new Schema(List.of(Field.notNullable("url", ArrowType.Utf8.INSTANCE)));
        try (VortexWriter w = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), arrow, ALLOCATOR).build()) {
            for (int from = 0; from < values.length; from += CHUNK) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(arrow, ALLOCATOR)) {
                    VarCharVector v = (VarCharVector) root.getVector("url");
                    root.allocateNew();
                    for (int i = 0; i < CHUNK; i++) {
                        v.setSafe(i, values[from + i].getBytes(StandardCharsets.UTF_8));
                    }
                    root.setRowCount(CHUNK);
                    try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR); ArrowSchema as = ArrowSchema.allocateNew(ALLOCATOR)) {
                        Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, as);
                        w.writeBatch(array.memoryAddress(), as.memoryAddress());
                    }
                }
            }
        }
        return file;
    }

    /// How many dict layouts a tree holds: a `vortex.dict` whose child is a layout, not an array encoding.
    private static long dictLayouts(List<String> tree) {
        long count = 0;
        for (int i = 0; i + 1 < tree.size(); i++) {
            if (tree.get(i).strip().equals("vortex.dict") && tree.get(i + 1).strip().equals("vortex.flat")) {
                count++;
            }
        }
        return count;
    }

    private static String[] readAll(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        try (var reader = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = reader.scan(ScanOptions.columns(ColumnName.of("url")))) {
            while (iter.hasNext()) {
                try (var chunk = iter.next()) {
                    VarBinArray strings = (VarBinArray) chunk.column(ColumnName.of("url"));
                    for (long i = 0; i < strings.length(); i++) {
                        out.add(strings.getString(i));
                    }
                }
            }
        }
        return out.toArray(String[]::new);
    }
}
