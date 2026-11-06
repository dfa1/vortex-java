package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
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
