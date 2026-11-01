package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.ImmutableScanOptions;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.parquet.ImportOptions;
import io.github.dfa1.vortex.parquet.ParquetExporter;
import io.github.dfa1.vortex.parquet.ParquetImporter;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/// A Parquet file imported by [ParquetImporter] reads in vortex-jni with the values and nulls the
/// Parquet file holds. The Parquet input is written by [ParquetExporter] from a Java-written Vortex
/// file, so each case controls the exact Parquet column shape the Raincloud corpus tripped on.
class ParquetImportInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName TS = ColumnName.of("ts");
    /// More than two zones (8192 rows), so the filtered read prunes and the range read slices.
    private static final int ROWS = 3 * 8192;
    private static final long START_MICROS = 1_704_067_200_000_000L; // 2024-01-01T00:00:00Z

    static {
        NativeLoader.loadJni();
    }

    /// An OPTIONAL `TIMESTAMP` column with nulls, as in Raincloud's `bi-telco` (#627: the import
    /// failed as "declared REQUIRED"). Nulls only in the second half and small import chunks, so the
    /// writer's repartition coalesces a chunk without nulls followed by one with nulls.
    @Test
    void nullableTimestampWithNulls_jniReadsImportedFile(@TempDir Path tmp) throws Exception {
        // Given
        IntPredicate isNull = i -> i % 7 == 0 && i >= ROWS / 2;
        List<Long> expected = new ArrayList<>(ROWS);
        List<Instant> instants = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            long micros = START_MICROS + i * 90_000_000L;
            expected.add(isNull.test(i) ? null : micros);
            instants.add(isNull.test(i) ? null : Instant.EPOCH.plusNanos(micros * 1_000L));
        }
        Path parquet = toParquet(tmp, TimestampDtype.of(TimeUnit.Microseconds, null, true), instants);
        Path imported = tmp.resolve("imported.vortex");
        long threshold = expected.get(ROWS / 2 + 1);

        // When
        ParquetImporter.importParquet(parquet, imported, ImportOptions.defaults().withChunkSize(4096));
        List<Long> all = readWithJni(imported, ScanOptions.builder());
        // a filtered and a row-range read prune and slice, which a full scan never does
        List<Long> filtered = readWithJni(imported, ScanOptions.builder().filter(Expression.binary(
                Expression.BinaryOp.GTE, Expression.column("ts"),
                Expression.literalTimestamp(threshold, Expression.TimeUnit.MICROSECONDS, null))));
        List<Long> range = readWithJni(imported, ScanOptions.builder().rowRangeBegin(1000).rowRangeEnd(20_000));

        // Then
        assertThat(all).isEqualTo(expected);
        assertThat(filtered).isEqualTo(expected.stream().filter(v -> v != null && v >= threshold).toList());
        assertThat(range).isEqualTo(expected.subList(1000, 20_000));
    }

    private static Path toParquet(Path tmp, DType.Extension dtype, List<Instant> values) throws IOException {
        Path vortex = tmp.resolve("source.vortex");
        DType.Struct schema = new DType.Struct(List.of(TS), List.of(dtype), false);
        try (FileChannel channel = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(TS, values));
        }
        Path parquet = tmp.resolve("source.parquet");
        ParquetExporter.exportParquet(vortex, parquet);
        return parquet;
    }

    private static List<Long> readWithJni(Path file, ImmutableScanOptions.Builder options) throws IOException {
        ScanOptions opts = options.projection(Expression.select(new String[]{"ts"}, Expression.root())).build();
        List<Long> out = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    TimeStampVector vec = (TimeStampVector) root.getVector("ts");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        out.add(vec.isNull(i) ? null : vec.get(i));
                    }
                }
            }
        }
        return out;
    }
}
