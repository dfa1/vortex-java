package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Round-trip tests for [io.github.dfa1.vortex.reader.ScanIterator#columnZoneStats] — the read-side
/// surface for ADR 0013 §6 aggregate push-down. One zone per written chunk; each carries the
/// min/max/sum/null-count the writer embedded in the chunk's `ArrayStats`, read without decoding any
/// data segment.
class ColumnZoneStatsTest {

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of("id"),
            List.of(DType.I64),
            false);

    private static final DType.Struct F64_SCHEMA = new DType.Struct(
            List.of("v"),
            List.of(DType.F64),
            false);

    // Three chunks of id: [1..50], [51..100], [101..150]. Per-zone sums are the closed-form
    // triangular sums, chosen so a wrong fold (e.g. summing the wrong zone) is visible.
    private static Path writeThreeChunks(Path tmp) throws IOException {
        Path file = tmp.resolve("zones.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("id", range(1L, 50L)));
            sut.writeChunk(Map.of("id", range(51L, 100L)));
            sut.writeChunk(Map.of("id", range(101L, 150L)));
        }
        return file;
    }

    private static long[] range(long from, long to) {
        long[] arr = new long[(int) (to - from + 1)];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = from + i;
        }
        return arr;
    }

    private static ReadRegistry registry() {
        return ReadRegistry.builder().registerServiceLoaded().build();
    }

    private static List<ArrayStats> zoneStats(Path file, String column) throws IOException {
        try (VortexReader vf = VortexReader.open(file, registry());
             var iter = vf.scan(new ScanOptions(List.of(), null, ScanOptions.NO_LIMIT))) {
            return iter.columnZoneStats(column);
        }
    }

    @Test
    void perZoneMinMaxSumNullCount(@TempDir Path tmp) throws IOException {
        // Given — three I64 chunks with known min/max/sum per zone
        Path file = writeThreeChunks(tmp);

        // When
        List<ArrayStats> result = zoneStats(file, "id");

        // Then — one zone per chunk, each carrying that chunk's stats (no data decoded)
        assertThat(result).hasSize(3);
        assertThat(result.get(0).min()).isEqualTo(1L);
        assertThat(result.get(0).max()).isEqualTo(50L);
        assertThat(result.get(0).sum()).isEqualTo(1275L);   // 1+..+50
        assertThat(result.get(0).nullCount()).isEqualTo(0L);
        assertThat(result.get(1).min()).isEqualTo(51L);
        assertThat(result.get(1).max()).isEqualTo(100L);
        assertThat(result.get(1).sum()).isEqualTo(3775L);   // 51+..+100
        assertThat(result.get(2).min()).isEqualTo(101L);
        assertThat(result.get(2).max()).isEqualTo(150L);
        assertThat(result.get(2).sum()).isEqualTo(6275L);   // 101+..+150
    }

    @Test
    void summingPerZoneSumsEqualsFileTotal(@TempDir Path tmp) throws IOException {
        // Given — the whole-zone tier of an aggregate: SUM(id) folds per-zone sums, no data decode
        Path file = writeThreeChunks(tmp);

        // When
        long total = zoneStats(file, "id").stream()
                .mapToLong(s -> (Long) s.sum())
                .sum();

        // Then — equals 1+..+150
        assertThat(total).isEqualTo(11325L);
    }

    @Test
    void floatColumnZoneSumsAreDoubles(@TempDir Path tmp) throws IOException {
        // Given — a float column so the sum stat decodes as Double, not Long
        Path file = tmp.resolve("f64.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, F64_SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("v", new double[]{1.5, 2.5, 3.0}));
        }

        // When
        List<ArrayStats> result = zoneStats(file, "v");

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().sum()).isEqualTo(7.0);
    }

    @Test
    void missingColumnYieldsEmptyStatsPerZone(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeThreeChunks(tmp);

        // When — a column that does not exist still returns one entry per zone, all empty
        List<ArrayStats> result = zoneStats(file, "does_not_exist");

        // Then — aligned with chunk count, no stats
        assertThat(result).hasSize(3).allSatisfy(s -> assertThat(s).isEqualTo(ArrayStats.empty()));
    }
}
