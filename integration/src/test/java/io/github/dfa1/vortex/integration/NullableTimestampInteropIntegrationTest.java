package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.parquet.ImportOptions;
import io.github.dfa1.vortex.parquet.ParquetExporter;
import io.github.dfa1.vortex.parquet.ParquetImporter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A nullable timestamp column is written as vortex-jni writes it: `vortex.datetimeparts`, with the
/// validity on its days child as Rust's `split_temporal` keeps it, and no validity at all when every
/// row is valid. The Java side used to decline datetimeparts for any chunk holding a null, writing
/// `vortex.ext` over a masked i64, so the column's encoding changed with where its nulls fell.
///
/// Only the data column's root encoding and where its validity sits are compared: the cascade under
/// the days child and the zone-stats table still differ from Rust with or without nulls (#634).
class NullableTimestampInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName TS = ColumnName.of("ts");
    private static final DType.Extension TS_DTYPE = TimestampDtype.of(TimeUnit.Microseconds, null, true);
    private static final int ROWS = 4 * 8192;
    private static final long START_MICROS = 1_704_067_200_000_000L; // 2024-01-01T00:00:00Z
    private static final long[] JITTER_MICROS = new Random(634).longs(ROWS, 0, 60_000_000L).toArray();

    static {
        NativeLoader.loadJni();
    }

    static Stream<Arguments> shapes() {
        return Stream.of(
                // nullable but every row valid: written without a validity, as Rust's AllValid
                Arguments.of("no nulls", (IntPredicate) _ -> false),
                Arguments.of("every 7th null", (IntPredicate) i -> i % 7 == 0),
                Arguments.of("one null", (IntPredicate) i -> i == 5),
                Arguments.of("nulls in the second half", (IntPredicate) i -> i >= ROWS / 2 && i % 3 == 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void javaWritesTheEncodingTreeJniWrites(String name, IntPredicate isNull, @TempDir Path tmp) throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, isNull);
        List<String> expected = EncodingTrees.of(jniFile);

        // When
        writeWithJava(javaFile, isNull);

        // Then
        assertThat(dataColumn(expected).getFirst()).contains("vortex.datetimeparts");
        assertDataColumnMatches(EncodingTrees.of(javaFile), expected);
    }

    /// The Parquet import path hands the writer batches with and without nulls, which its
    /// repartition coalesces into one chunk; that chunk is datetimeparts too.
    @Test
    void parquetImport_writesTheEncodingTreeJniWrites(@TempDir Path tmp) throws Exception {
        // Given
        IntPredicate isNull = i -> i >= ROWS / 2 && i % 3 == 0;
        Path jniFile = tmp.resolve("jni.vortex");
        writeWithJni(jniFile, isNull);
        Path source = tmp.resolve("source.vortex");
        writeWithJava(source, isNull);
        Path parquet = tmp.resolve("source.parquet");
        ParquetExporter.exportParquet(source, parquet);
        Path imported = tmp.resolve("imported.vortex");

        // When
        ParquetImporter.importParquet(parquet, imported, ImportOptions.defaults().withChunkSize(4096));

        // Then
        assertDataColumnMatches(EncodingTrees.of(imported), EncodingTrees.of(jniFile));
    }

    /// Same root encoding for the data column, and a validity (`vortex.bool`) in its days child
    /// exactly when Rust writes one.
    private static void assertDataColumnMatches(List<String> actual, List<String> expected) {
        List<String> actualColumn = dataColumn(actual);
        List<String> expectedColumn = dataColumn(expected);
        assertThat(actualColumn.getFirst()).isEqualTo(expectedColumn.getFirst());
        assertThat(firstChild(actualColumn).stream().anyMatch(l -> l.contains("vortex.bool")))
                .isEqualTo(firstChild(expectedColumn).stream().anyMatch(l -> l.contains("vortex.bool")));
    }

    /// The data column's encoding subtree: the lines under the zoned layout's first flat, up to the
    /// stats table's flat.
    private static List<String> dataColumn(List<String> tree) {
        int start = tree.indexOf("    vortex.flat") + 1;
        int end = tree.subList(start, tree.size()).indexOf("    vortex.flat");
        return tree.subList(start, end < 0 ? tree.size() : start + end);
    }

    /// The first child (days) subtree of a data column subtree.
    private static List<String> firstChild(List<String> column) {
        int depth = indent(column.getFirst()) + 2;
        int end = 2;
        while (end < column.size() && indent(column.get(end)) > depth) {
            end++;
        }
        return column.subList(1, end);
    }

    private static int indent(String line) {
        return line.length() - line.stripLeading().length();
    }

    /// One row about every 90 s with a jitter of up to a minute: not an arithmetic sequence, which
    /// Rust would write as `vortex.sequence` instead of splitting it (#634).
    private static long micros(int i) {
        return START_MICROS + i * 90_000_000L + JITTER_MICROS[i];
    }

    private static void writeWithJni(Path path, IntPredicate isNull) throws Exception {
        Schema schema = new Schema(List.of(Field.nullable("ts",
                new ArrowType.Timestamp(org.apache.arrow.vector.types.TimeUnit.MICROSECOND, null))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            TimeStampMicroVector vector = (TimeStampMicroVector) root.getVector("ts");
            vector.setInitialCapacity(ROWS);
            vector.allocateNew();
            for (int i = 0; i < ROWS; i++) {
                if (isNull.test(i)) {
                    vector.setNull(i);
                } else {
                    vector.setSafe(i, micros(i));
                }
            }
            vector.setValueCount(ROWS);
            root.setRowCount(ROWS);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    private static void writeWithJava(Path path, IntPredicate isNull) throws IOException {
        List<Instant> values = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            values.add(isNull.test(i) ? null : Instant.EPOCH.plusNanos(micros(i) * 1_000L));
        }
        DType.Struct schema = new DType.Struct(List.of(TS), List.of(TS_DTYPE), false);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(TS, values));
        }
    }
}
