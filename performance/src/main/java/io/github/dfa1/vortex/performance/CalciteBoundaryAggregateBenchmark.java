package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.calcite.VortexTable;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/// A/B benchmark for the Calcite boundary-zone aggregate fold (ADR 0013 §6 tier-2, ADR 0018 boundary
/// tier) against the pre-feature full-scan baseline — the same `SUM(val) WHERE id BETWEEN a AND b`
/// answered two ways so the speedup is visible.
///
/// Fixture: a single Vortex file of 1,000,000 rows written in 10,000-row chunks, so the file carries
/// 100 chunks (chunk `k` spans ids `[10000k, 10000k+9999]`) — one zone per chunk, zone maps enabled
/// so every zone records min/max/sum/null-count. The `id` column is the ascending clustered key
/// `0 .. 999_999`; `val` is a deterministic seeded pattern reduced by the aggregate.
///
/// Both `@Benchmark` methods compute `SUM(val) WHERE id >= lo AND id <= hi` (the lowering of
/// `id BETWEEN lo AND hi`). The endpoints land mid-chunk near the file ends, so the range splits the
/// chunks into a few OUT zones (pruned), exactly two BOUNDARY zones the range cuts through, and many
/// INTERIOR zones fully inside the range. Concretely for the [#range] cases (chunk = 10,000 rows):
///
/// - `wide`   `id ∈ [15_000, 985_000]`  → chunk 0 OUT, chunk 1 BOUNDARY, chunks 2..97 IN (96),
///   chunk 98 BOUNDARY, chunk 99 OUT. Baseline decodes chunks 1..98 = 98; fold decodes 2.
/// - `medium` `id ∈ [245_000, 755_000]` → chunk 24 BOUNDARY, chunks 25..74 IN (50), chunk 75
///   BOUNDARY. Baseline decodes chunks 24..75 = 52; fold decodes 2.
/// - `narrow` `id ∈ [495_000, 505_000]` → chunk 49 BOUNDARY, chunk 50 BOUNDARY, no IN zone.
///   Baseline decodes chunks 49,50 = 2; fold decodes 2 (parity — no interior to skip).
///
/// - [#boundaryFold] is the optimized path: [VortexTable#filteredFold] folds the INTERIOR zones from
///   their footer `SUM` statistics with no decode and only decodes the two BOUNDARY chunks under a
///   row mask. Driven directly (not over JDBC) on purpose: the SQL path would fold in Calcite parse /
///   validate / plan / rule-firing cost per query, which is unrelated to — and would mask — the
///   data-decode win this A/B isolates. The baseline is also a bare reader path, so both sides differ
///   only in how many chunks they decode.
/// - [#fullScanBaseline] is the pre-feature cost: a [VortexReader] scan with the same range as a
///   [RowFilter]. Zone-map pruning still drops the fully-OUT chunks, but every surviving chunk —
///   INTERIOR and BOUNDARY alike — is decoded and the predicate evaluated per row. This is exactly
///   the work the old "abandon to a zone-map-pruned scan" did before the boundary fold landed.
///
/// Expected result: [#boundaryFold] decodes 2 chunks regardless of range width, while
/// [#fullScanBaseline] decodes one chunk per surviving zone, so the fold wins in proportion to the
/// interior-chunk count — large on `wide`, smaller on `medium`, parity on `narrow`. The mode is
/// [Mode#AverageTime] in milliseconds because the metric of interest is per-query latency. Both
/// methods return the sum so JMH cannot dead-code-eliminate the work; [#setup] asserts the two paths
/// agree once before timing so the A/B is honest.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(value = 1, jvmArgsAppend = {
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
})
public class CalciteBoundaryAggregateBenchmark {

    private static final int CHUNK_SIZE = 10_000;
    private static final int CHUNKS = 100;
    private static final int TOTAL_ROWS = CHUNK_SIZE * CHUNKS;
    private static final long SEED = 42L;
    private static final String AGG_COLUMN = "val";

    /// The `WHERE id BETWEEN lo AND hi` range width, which sets how many INTERIOR chunks the fold
    /// skips — the dimension the win scales with (see the class doc for the per-case chunk math).
    @Param({"wide", "medium", "narrow"})
    public String range;

    private Path file;
    private VortexTable table;
    private RowFilter filter;
    private ScanOptions baselineOptions;
    private long lo;
    private long hi;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        long[] bounds = bounds(range);
        lo = bounds[0];
        hi = bounds[1];

        file = Files.createTempFile("calcite-boundary-bench", ".vortex");
        writeFixture(file);

        table = new VortexTable(file);
        // BETWEEN lowers to `id >= lo AND id <= hi`; the same filter drives both paths.
        filter = RowFilter.and(RowFilter.gte("id", lo), RowFilter.lte("id", hi));
        baselineOptions = ScanOptions.columns("id", AGG_COLUMN).withFilter(filter);

        // Honesty check: the optimized fold and the full-scan baseline must agree before we time them.
        long folded = boundaryFold();
        long scanned = fullScanBaseline();
        if (folded != scanned) {
            throw new IllegalStateException(
                    "boundary fold (" + folded + ") disagrees with full scan (" + scanned + ") for range=" + range);
        }
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException {
        Files.deleteIfExists(file);
    }

    /// Optimized path: fold the filtered `SUM(val)` over the boundary-zone tiers, decoding only the
    /// two boundary chunks while the interior zones are summed from footer statistics.
    ///
    /// @return the filtered sum of `val`
    @Benchmark
    public long boundaryFold() {
        Optional<VortexTable.FilteredFold> fold = table.filteredFold(filter, AGG_COLUMN);
        return fold.orElseThrow(() -> new IllegalStateException("fold abandoned for range=" + range))
                .sum().longValue();
    }

    /// Baseline path: scan with the same range filter (zone-map pruning drops fully-out chunks), then
    /// decode every surviving chunk and sum the `val` of rows whose `id` falls in the range — the
    /// pre-feature cost the boundary fold replaces.
    ///
    /// @return the filtered sum of `val`
    /// @throws IOException if the file cannot be read
    @Benchmark
    public long fullScanBaseline() throws IOException {
        long sum = 0L;
        try (VortexReader reader = VortexReader.open(file);
             var iter = reader.scan(baselineOptions)) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    LongArray id = c.column("id");
                    LongArray val = c.column(AGG_COLUMN);
                    long n = id.length();
                    for (long i = 0; i < n; i++) {
                        long key = id.getLong(i);
                        if (key >= lo && key <= hi) {
                            sum += val.getLong(i);
                        }
                    }
                }
            }
        }
        return sum;
    }

    /// The `[lo, hi]` endpoints for a named range, chosen so the cut points land mid-chunk (see the
    /// class doc): `wide` skips 96 interior chunks, `medium` 50, `narrow` none.
    private static long[] bounds(String range) {
        return switch (range) {
            case "wide" -> new long[]{15_000L, 985_000L};
            case "medium" -> new long[]{245_000L, 755_000L};
            case "narrow" -> new long[]{495_000L, 505_000L};
            default -> throw new IllegalArgumentException("unknown range: " + range);
        };
    }

    /// Writes the 100-chunk fixture: `id` ascending `0 .. TOTAL_ROWS-1` (one contiguous run per
    /// chunk) and `val` a deterministic seeded pattern, with zone maps enabled so each chunk emits
    /// the per-zone `SUM` the interior-zone fold reads.
    private static void writeFixture(Path file) throws IOException {
        DType.Struct schema = DType.structBuilder()
                .field("id", DType.I64)
                .field("val", DType.I64)
                .build();
        // enableZoneMaps=true emits the per-chunk min/max/sum/null-count the interior-zone fold reads.
        WriteOptions opts = new WriteOptions(CHUNK_SIZE, true, 0.90, 0, true, false);
        java.util.Random rng = new java.util.Random(SEED);
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(ch, schema, opts)) {
            for (int c = 0; c < CHUNKS; c++) {
                long[] id = new long[CHUNK_SIZE];
                long[] val = new long[CHUNK_SIZE];
                for (int i = 0; i < CHUNK_SIZE; i++) {
                    id[i] = (long) c * CHUNK_SIZE + i;
                    val[i] = rng.nextInt(1_000);
                }
                writer.writeChunk(Map.of("id", id, "val", val));
            }
        }
    }
}
