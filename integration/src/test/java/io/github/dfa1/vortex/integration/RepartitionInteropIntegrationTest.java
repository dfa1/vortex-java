package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The writers coalesce `writeChunk` batches into chunks of the fewest 8192-row blocks reaching
/// 1 MiB, measured by Rust's exact uncompressed size (vortex-data/vortex#10445): a string of at
/// most 12 bytes costs only its view, and a block with no null carries no validity. The same
/// column through vortex-jni and vortex-java must cut the same chunks.
@Tag("slow") // about 1 s a case: each writes the column with both writers
class RepartitionInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName S = ColumnName.of("s");
    private static final int BATCHES = 4;
    private static final int BATCH_ROWS = 60_000;
    // 600k rows: past the 64 blocks of I16 that reach 1 MiB
    private static final int SHORT_BATCHES = 10;

    static {
        NativeLoader.loadJni();
    }

    static Stream<Arguments> columns() {
        Random random = new Random(7);
        // distinct values, so neither writer dictionary-encodes the column: dict codes chunk by
        // their own size
        int[] lengths = random.ints(BATCHES * BATCH_ROWS, 0, 24).toArray();
        return Stream.of(
                Arguments.of("inlined strings", false, (IntFunction<String>) row -> "v%07d".formatted(row)),
                Arguments.of("out-of-line strings", false, (IntFunction<String>) row -> "value-%010d".formatted(row)),
                Arguments.of("mixed lengths", false, (IntFunction<String>) row -> "%06d".formatted(row) + "x".repeat(lengths[row])),
                Arguments.of("nullable, no nulls", true, (IntFunction<String>) row -> "v%07d".formatted(row)),
                Arguments.of("nullable, sparse nulls", true,
                        (IntFunction<String>) row -> row % 50_000 == 7 ? null : "v%07d".formatted(row)),
                Arguments.of("nullable, null runs", true,
                        (IntFunction<String>) row -> row / 8192 % 3 == 0 ? null : "value-%010d".formatted(row)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("columns")
    void javaCutsTheChunksJniCuts(String name, boolean nullable, IntFunction<String> valueAt, @TempDir Path tmp)
            throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, nullable, valueAt);

        // When
        writeWithJava(javaFile, nullable, valueAt);

        // Then
        long[] expected = chunkRowCounts(jniFile);
        assertThat(expected).as("Rust cut more than one chunk").hasSizeGreaterThan(1);
        assertThat(chunkRowCounts(javaFile)).isEqualTo(expected);
    }

    // At 2 B a row of I16 values, a block's validity moves the cut: 64 blocks reach 1 MiB without a
    // bitmap, 61 with one. 5000-row null runs straddle every block, so a block measured per batch
    // instead of from the column's first row would carry no bitmap.
    static Stream<Arguments> nullPatterns() {
        return Stream.of(
                Arguments.of("no nulls", (IntPredicate) row -> false),
                Arguments.of("sparse nulls", (IntPredicate) row -> row % 50_000 == 7),
                Arguments.of("null blocks", (IntPredicate) row -> row / 8192 % 3 == 0),
                Arguments.of("null runs across blocks", (IntPredicate) row -> row / 5000 % 2 == 0),
                Arguments.of("all null", (IntPredicate) row -> true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullPatterns")
    void javaCutsTheChunksJniCuts_nullableI16(String name, IntPredicate isNull, @TempDir Path tmp) throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeShortsWithJni(jniFile, isNull);

        // When
        writeShortsWithJava(javaFile, isNull);

        // Then
        long[] expected = chunkRowCounts(jniFile);
        assertThat(expected).as("Rust cut more than one chunk").hasSizeGreaterThan(1);
        assertThat(chunkRowCounts(javaFile)).isEqualTo(expected);
    }

    private static void writeShortsWithJni(Path path, IntPredicate isNull) throws Exception {
        Schema schema = new Schema(List.of(Field.nullable("s", new ArrowType.Int(16, true))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int batch = 0; batch < SHORT_BATCHES; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    SmallIntVector s = (SmallIntVector) root.getVector("s");
                    s.allocateNew(BATCH_ROWS);
                    for (int i = 0; i < BATCH_ROWS; i++) {
                        int row = batch * BATCH_ROWS + i;
                        if (isNull.test(row)) {
                            s.setNull(i);
                        } else {
                            s.set(i, (short) row);
                        }
                    }
                    root.setRowCount(BATCH_ROWS);
                    JniFiles.writeBatch(ALLOCATOR, writer, root);
                }
            }
        }
    }

    private static void writeShortsWithJava(Path path, IntPredicate isNull) throws Exception {
        DType.Struct schema = new DType.Struct(List.of(S), List.of(new DType.Primitive(PType.I16, true)), false);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            for (int batch = 0; batch < SHORT_BATCHES; batch++) {
                short[] s = new short[BATCH_ROWS];
                boolean[] valid = new boolean[BATCH_ROWS];
                for (int i = 0; i < BATCH_ROWS; i++) {
                    int row = batch * BATCH_ROWS + i;
                    valid[i] = !isNull.test(row);
                    s[i] = valid[i] ? (short) row : 0;
                }
                writer.writeChunk(Map.of(S, new NullableData(s, valid)));
            }
        }
    }

    private static long[] chunkRowCounts(Path file) throws IOException {
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(ScanOptions.all())) {
            return scan.chunkRowCounts();
        }
    }

    private static void writeWithJni(Path path, boolean nullable, IntFunction<String> valueAt) throws Exception {
        Field field = nullable ? Field.nullable("s", new ArrowType.Utf8()) : Field.notNullable("s", new ArrowType.Utf8());
        Schema schema = new Schema(List.of(field));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int batch = 0; batch < BATCHES; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    VarCharVector s = (VarCharVector) root.getVector("s");
                    s.allocateNew(BATCH_ROWS);
                    for (int i = 0; i < BATCH_ROWS; i++) {
                        String value = valueAt.apply(batch * BATCH_ROWS + i);
                        if (value == null) {
                            s.setNull(i);
                        } else {
                            s.setSafe(i, value.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    root.setRowCount(BATCH_ROWS);
                    JniFiles.writeBatch(ALLOCATOR, writer, root);
                }
            }
        }
    }

    private static void writeWithJava(Path path, boolean nullable, IntFunction<String> valueAt) throws Exception {
        DType.Struct schema = new DType.Struct(List.of(S), List.of(new DType.Utf8(nullable)), false);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            for (int batch = 0; batch < BATCHES; batch++) {
                String[] s = new String[BATCH_ROWS];
                boolean[] valid = new boolean[BATCH_ROWS];
                for (int i = 0; i < BATCH_ROWS; i++) {
                    String value = valueAt.apply(batch * BATCH_ROWS + i);
                    s[i] = value == null ? "" : value;
                    valid[i] = value != null;
                }
                writer.writeChunk(Map.of(S, nullable ? new NullableData(s, valid) : s));
            }
        }
    }
}
