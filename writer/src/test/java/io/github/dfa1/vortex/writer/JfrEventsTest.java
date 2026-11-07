package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.testing.JfrRecorderBase;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;

class JfrEventsTest extends JfrRecorderBase {

    private static final ColumnName ID = ColumnName.of("id");
    private static final DType.Struct SCHEMA = new DType.Struct(List.of(ID), List.of(DTypes.I64), false);

    @Test
    void write_recordsSegmentEncoded(@TempDir Path tmp) throws IOException {
        // Given / When
        write(tmp, LegacyZoneMaps.OPTIONS, range(1, 100));

        // Then
        var encoded = events("SegmentEncoded");
        assertThat(encoded).isNotEmpty();
        assertThat(encoded).allSatisfy(e -> {
            assertThat(e.getString("encoding")).startsWith("vortex.").isNotEmpty();
            assertThat(e.getInt("bufferCount")).isNotNegative();
            assertThat(e.getLong("encodedSize")).isNotNegative();
        });
    }

    @Test
    void write_defaultExecutor_recordsNoSegmentWait(@TempDir Path tmp) throws IOException {
        // Given / When
        write(tmp, LegacyZoneMaps.OPTIONS, range(1, 100), range(101, 200));

        // Then
        assertThat(events("SegmentWait")).isEmpty();
    }

    @Test
    void write_slowExecutor_recordsSegmentWait(@TempDir Path tmp) throws IOException {
        // Given an executor that sleeps before each task, so the head is never done when awaited
        Executor slow = task -> ForkJoinPool.commonPool().execute(() -> {
            try {
                Thread.sleep(Duration.ofMillis(200));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            task.run();
        });

        // When
        write(tmp, LegacyZoneMaps.OPTIONS.withExecutor(slow), range(1, 100));

        // Then
        var waits = events("SegmentWait");
        assertThat(waits).isNotEmpty();
        assertThat(waits).anySatisfy(e -> {
            assertThat(e.getDuration()).isGreaterThan(Duration.ofMillis(50));
            assertThat(e.getInt("segmentsInFlight")).isPositive();
        });
    }

    @Test
    void scan_filterPrunesChunks_recordsChunkFilter(@TempDir Path tmp) throws IOException {
        // Given chunks 1..50, 51..100, 101..150
        Path file = write(tmp, LegacyZoneMaps.OPTIONS, range(1, 50), range(51, 100), range(101, 150));

        // When
        scan(file, ScanFilter.eq(ID, 75L));

        // Then
        var filter = events("ChunkFilter");
        assertThat(filter).hasSize(1);
        assertThat(filter.getFirst().getInt("totalChunks")).isEqualTo(3);
        assertThat(filter.getFirst().getInt("chunksPruned")).isEqualTo(2);
        assertThat(filter.getFirst().getInt("chunksWithoutMatch")).isZero();
        assertThat(filter.getFirst().getInt("chunksReturned")).isEqualTo(1);
        assertThat(filter.getFirst().getLong("rowsReturned")).isEqualTo(1);
    }

    @Test
    void scan_filterMissesInsideZone_recordsChunkWithoutMatch(@TempDir Path tmp) throws IOException {
        // Given a single chunk {1, 100}: id = 50 is within its min/max but absent
        Path file = write(tmp, LegacyZoneMaps.OPTIONS, new long[]{1, 100});

        // When
        scan(file, ScanFilter.eq(ID, 50L));

        // Then
        var filter = events("ChunkFilter");
        assertThat(filter).hasSize(1);
        assertThat(filter.getFirst().getInt("totalChunks")).isEqualTo(1);
        assertThat(filter.getFirst().getInt("chunksPruned")).isZero();
        assertThat(filter.getFirst().getInt("chunksWithoutMatch")).isEqualTo(1);
        assertThat(filter.getFirst().getInt("chunksReturned")).isZero();
        assertThat(filter.getFirst().getLong("rowsReturned")).isZero();
    }

    @Test
    void scan_withoutFilter_recordsNoChunkFilter(@TempDir Path tmp) throws IOException {
        // Given
        Path file = write(tmp, LegacyZoneMaps.OPTIONS, range(1, 50));

        // When
        scan(file, null);

        // Then
        assertThat(events("ChunkFilter")).isEmpty();
    }

    @Test
    void write_runnyColumn_recordsCandidatesAndAcceptedWinner(@TempDir Path tmp) throws IOException {
        // Given a column of long runs, which run-length encoding beats a frame of reference on
        long[] runny = new long[16_384];
        for (int i = 0; i < runny.length; i++) {
            runny[i] = 1_000_000L + i / 512;
        }

        // When
        write(tmp, WriteOptions.defaults(), runny);

        // Then every event carries the column and the cascade, and the winner was kept
        var candidates = events("Candidate");
        assertThat(candidates).isNotEmpty();
        assertThat(candidates).allSatisfy(e -> {
            assertThat(e.getString("column")).isEqualTo("id");
            assertThat(e.getInt("segment")).isGreaterThanOrEqualTo(-1); // -1: the global-dict probe
            assertThat(e.getInt("cascadeDepth")).isEqualTo(
                    e.getString("cascadePath").isEmpty() ? 0 : e.getString("cascadePath").split("/").length);
        });
        assertThat(candidates).anySatisfy(e -> {
            assertThat(e.getString("schemeCandidate")).isEqualTo("fastlanes.rle");
            assertThat(e.getString("verdict")).isEqualTo("measured");
            assertThat(e.getBoolean("sample")).isTrue();
            assertThat(e.getLong("sampleNbytes")).isPositive();
        });
        assertThat(candidates).anySatisfy(e ->
                assertThat(e.getString("schemeCandidate")).isEqualTo("fastlanes.for"));
        var winners = events("Winner");
        assertThat(winners).anySatisfy(e -> {
            assertThat(e.getString("cascadePath")).isEmpty();
            assertThat(e.getString("schemeChosen")).isNotEmpty();
            assertThat(e.getBoolean("accepted")).isTrue();
            assertThat(e.getDouble("achievedRatio")).isGreaterThan(1.0);
        });
        var compress = events("Compress");
        assertThat(compress).anySatisfy(e -> {
            assertThat(e.getString("cascadePath")).isEmpty();
            // the writer repartitions a column into 8192-row blocks
            assertThat(e.getLong("arrayLen")).isEqualTo(8192);
            assertThat(e.getLong("inputNbytes")).isEqualTo(8192 * 8L);
            assertThat(e.getLong("compressedNbytes")).isLessThan(8192 * 8L);
        });
    }

    private static Path write(Path tmp, WriteOptions options, long[]... chunks) throws IOException {
        Path file = tmp.resolve("jfr.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, options)) {
            for (long[] chunk : chunks) {
                sut.writeChunk(Map.of(ID, chunk));
            }
        }
        return file;
    }

    private static void scan(Path file, ScanFilter filter) throws IOException {
        ScanOptions options = ScanOptions.all();
        if (filter != null) {
            options = options.withFilter(filter);
        }
        try (var reader = VortexReader.open(file);
             var scan = reader.scan(options)) {
            scan.forEachRemaining(chunk -> chunk.close());
        }
    }

    private static long[] range(long from, long to) {
        long[] values = new long[(int) (to - from + 1)];
        for (int i = 0; i < values.length; i++) {
            values[i] = from + i;
        }
        return values;
    }
}
