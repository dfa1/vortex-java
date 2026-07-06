package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.ChunkedByteArray;
import io.github.dfa1.vortex.reader.array.DictLongArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.LazyAlpDoubleArray;
import io.github.dfa1.vortex.reader.array.LazyForLongArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.OffsetDoubleArray;
import io.github.dfa1.vortex.reader.array.OffsetLongArray;
import io.github.dfa1.vortex.reader.compute.Compute;
import io.github.dfa1.vortex.reader.compute.FilteredAggregate;
import io.github.dfa1.vortex.reader.compute.Predicate;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/// Baseline for the encoded-domain compute-kernel specialization of ADR 0013.
///
/// The fused compute kernel ([Compute#filteredSum(Array, Predicate, Array)]) today decodes every
/// element through the typed accessor: it reads `getLong(i)` / `getDouble(i)` per row, so an ALP or
/// Frame-of-Reference column is fully reconstructed into the value domain before a single comparison
/// or addition runs. The future work compares and reduces directly in the encoded integer domain
/// (ALP residuals, FoR offsets) without decoding. This benchmark pins the CURRENT decode-via-accessor
/// cost so that win is provable: the same `@Benchmark` methods will show the speedup once the
/// specialized kernels land.
///
/// One hundred million rows are written as `TOTAL_ROWS / CHUNK_ROWS` chunks of `CHUNK_ROWS` each
/// with `WriteOptions.cascading(3)`, so the writer picks real encodings and the four columns decode
/// to:
/// - `price` — `f64` rounded to two decimals, chosen for ALP, decodes to [LazyAlpDoubleArray].
/// - `measure` — `i64` with a large base and a bounded spread, chosen for Frame-of-Reference,
///   decodes to [LazyForLongArray].
/// - `category` — `i64` with sixteen distinct values, chosen for dictionary encoding, decodes to
///   [DictLongArray].
/// - `plain` — `i64` full-range random, with no encoding savings, decodes to
///   [MaterializedLongArray] as an apples baseline for the non-encoded cost.
///
/// At one hundred million rows each column spans ≈ 800 MB of values, far larger than any L3 cache,
/// so the kernel measurements are memory-bound. The write side refills and reuses one set of
/// `CHUNK_ROWS`-sized primitive arrays per chunk, keeping write heap at ≈ one chunk (≈ 32 MB)
/// rather than the full 3.2 GB. `@Setup(Level.Trial)` decodes every chunk once into per-column
/// lazy-array lists; because the columns decode to LAZY arrays (`LazyAlpDoubleArray`,
/// `LazyForLongArray`, …) that hold the ENCODED segment and decode per element on access, what is
/// held resident is the (compact) encoded data, and the kernel performs the decode during the
/// measured loop. Each `@Benchmark` method scans the full one-hundred-million-row dataset per op
/// by looping over its chunk list and accumulating.
///
/// `@Setup` asserts each decoded column (on the first chunk) is the expected encoded type and fails
/// loudly otherwise, so the baseline can never silently measure a plain column.
///
/// The fused kernel method `fusedFilteredSumAlp` is paired with a `forLoopFilteredSum` control: the
/// obvious hand-written accessor loop a developer writes WITHOUT the compute layer — no off-heap
/// bitmap, just `getDouble(i)` / `getLong(i)` and an accumulator. The standalone `forLoopX` baselines
/// measure the naive decode-per-element scan of each encoded column on its own. The paired methods
/// share the exact predicate and threshold constant so they cannot drift, giving the reference
/// points:
/// - `forLoopX` — the naive decode-per-element loop, the developer's baseline.
/// - `fusedFilteredSumAlp` — the current fused kernel, which still decodes through the accessor; the
///   `forLoopFilteredSum`→`fusedFilteredSumAlp` gap is the kernel's overhead (or benefit) today.
/// - the future encoded-domain specialization — measured against `forLoopFilteredSum`, which it must
///   beat by comparing and reducing in the integer domain instead of decoding every element.
///
/// Measurement discipline: single-fork numbers on the dict lanes swing ±20–50% across JVM
/// launches (C2 inlining luck — `fusedFilteredSumDict` measured 25–57 ms/op on identical code).
/// Numbers recorded in ADRs / javadoc must come from multi-fork runs (`-f 3`); treat any
/// single-fork delta under ≈ 1.5× as noise until an A/B on the same session confirms it.
///
/// Run: java -jar performance/target/benchmarks.jar ComputeKernelBenchmark
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
        "-Xmx4g"
})
public class ComputeKernelBenchmark {

    /// Total rows scanned per op — ≈ 800 MB per column, far larger than L3, so memory-bound.
    private static final int TOTAL_ROWS = 100_000_000;
    /// Rows per written/decoded chunk; the write side reuses one set of arrays this large.
    private static final int CHUNK_ROWS = 1_000_000;
    /// Number of chunks written and decoded (`TOTAL_ROWS / CHUNK_ROWS`).
    private static final int CHUNK_COUNT = TOTAL_ROWS / CHUNK_ROWS;
    private static final long SEED = 42L;

    /// Large base so the Frame-of-Reference reference value is non-zero and FoR is chosen.
    private static final long MEASURE_BASE = 1_700_000_000_000L;
    /// Bounded spread keeps the FoR residuals narrow (≈ 17 bits) without collapsing cardinality.
    private static final int MEASURE_SPREAD = 100_000;
    /// Low cardinality drives the dictionary encoding on the `category` column.
    private static final int CATEGORY_CARDINALITY = 16;

    private static final List<String> COLUMNS =
            List.of("price", "measure", "category", "plain");

    private static final DType.Struct SCHEMA = new DType.Struct(
            COLUMNS.stream().map(ColumnName::of).toList(),
            List.of(DType.F64, DType.I64, DType.I64, DType.I64),
            false);

    /// Selects ≈ half of the uniform `[0, 1000)` `price` column.
    private static final double PRICE_THRESHOLD = 500.0;
    /// Selects ≈ half of the `measure` column (base + uniform `[0, MEASURE_SPREAD)`).
    private static final long MEASURE_THRESHOLD = MEASURE_BASE + MEASURE_SPREAD / 2;
    /// Selects one of sixteen categories — ≈ 1/16 selectivity.
    private static final long CATEGORY_VALUE = 7L;

    private Path file;
    private ReadRegistry registry;
    private VortexReader reader;
    private final List<Chunk> chunks = new ArrayList<>();

    private final List<DoubleArray> priceChunks = new ArrayList<>();
    private final List<LongArray> measureChunks = new ArrayList<>();
    private final List<LongArray> categoryChunks = new ArrayList<>();
    private final List<LongArray> plainChunks = new ArrayList<>();

    @Setup(Level.Trial)
    public void setup() throws IOException {
        registry = ReadRegistry.loadAll();
        file = Files.createTempFile("compute-kernel-bench", ".vtx");
        write(file);

        reader = VortexReader.open(file, registry);
        if (reader.chunkCount() != CHUNK_COUNT) {
            throw new IllegalStateException(
                    "expected " + CHUNK_COUNT + " chunks, got " + reader.chunkCount());
        }

        for (int k = 0; k < reader.chunkCount(); k++) {
            Chunk decoded = reader.decodeChunk(k, COLUMNS);
            chunks.add(decoded);

            Array priceArr = decoded.column("price");
            Array measureArr = decoded.column("measure");
            Array categoryArr = decoded.column("category");
            Array plainArr = decoded.column("plain");

            if (k == 0) {
                System.out.printf("[ComputeKernelBenchmark] decoded column types:%n");
                System.out.printf("  price    -> %s%n", priceArr.getClass().getName());
                System.out.printf("  measure  -> %s%n", measureArr.getClass().getName());
                System.out.printf("  category -> %s%n", categoryArr.getClass().getName());
                System.out.printf("  plain    -> %s%n", plainArr.getClass().getName());

                requireType(priceArr, LazyAlpDoubleArray.class, "price");
                requireType(measureArr, LazyForLongArray.class, "measure");
                requireType(categoryArr, DictLongArray.class, "category");
                requireType(plainArr, MaterializedLongArray.class, "plain");
                System.out.printf("[ComputeKernelBenchmark] all four columns assert OK%n");
            }

            priceChunks.add((DoubleArray) priceArr);
            measureChunks.add((LongArray) measureArr);
            categoryChunks.add((LongArray) categoryArr);
            plainChunks.add((LongArray) plainArr);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        for (Chunk c : chunks) {
            c.close();
        }
        chunks.clear();
        priceChunks.clear();
        measureChunks.clear();
        categoryChunks.clear();
        plainChunks.clear();
        if (reader != null) {
            reader.close();
        }
        if (file != null) {
            Files.deleteIfExists(file);
        }
    }

    /// Fused one-pass pipeline: per chunk, [Compute#filteredSum(Array, Predicate, Array)] filters the
    /// ALP-encoded `price` column with `price > 500` and totals the FoR-encoded `measure` column over
    /// the selected rows in a single scan, with no intermediate selection bitmap. Decodes each price
    /// and (when selected) each measure through the accessor. Price and measure chunks are indexed in
    /// lockstep.
    ///
    /// @return the sum of `measure` over the rows where `price > 500` across the whole dataset
    @Benchmark
    public long fusedFilteredSumAlp() {
        long acc = 0;
        for (int k = 0; k < priceChunks.size(); k++) {
            DoubleArray priceArr = priceChunks.get(k);
            LongArray measureArr = measureChunks.get(k);
            acc += Compute.filteredSum(priceArr, new Predicate.Gt(PRICE_THRESHOLD), measureArr).longValue();
        }
        return acc;
    }

    /// Fused dict-filtered sum: per chunk, [Compute#filteredSum(Array, Predicate, Array)] filters the
    /// dict-encoded `category` column with `category == 7` and totals the FoR-encoded `measure`
    /// column over the selected rows (≈ 1/16 selectivity). This is the target workload of the dict
    /// code-scan fast lane: the filter side reads 100% of the rows, so its cost is the per-element
    /// dict accessor (chunk lookup + code read + value gather) unless the kernel scans the raw codes
    /// segment instead. Category and measure chunks are indexed in lockstep.
    ///
    /// Result: 762 ± 7 ms/op through the accessor chain (2026-07-02); 38.1 ± 9.2 ms/op with the
    /// `DictFilter` code-scan lane (2026-07-04, 3 forks — per-fork means span 28–57 ms/op, heavy
    /// C2 inlining luck; the best forks match the raw [#forLoopDictSegment()] ceiling). ≈ 20×
    /// typical, ≈ 30× on a good fork.
    ///
    /// @return the sum of `measure` over the rows where `category == 7` across the whole dataset
    @Benchmark
    public long fusedFilteredSumDict() {
        long acc = 0;
        for (int k = 0; k < categoryChunks.size(); k++) {
            LongArray categoryArr = categoryChunks.get(k);
            LongArray measureArr = measureChunks.get(k);
            acc += Compute.filteredSum(categoryArr, new Predicate.Eq(CATEGORY_VALUE), measureArr).longValue();
        }
        return acc;
    }

    /// Fused dict-filtered multi-column aggregate: per chunk,
    /// [Compute#filteredAggregate(Chunk, RowFilter, String)] evaluates `category == 7` as a whole
    /// [RowFilter] and folds `measure`'s `SUM` / `MIN` / `MAX` / non-null count over the selected
    /// rows — the kernel behind the Calcite boundary-chunk aggregate push-down, on the same
    /// dict-filtered workload as [#fusedFilteredSumDict()]. Folds the selected count and sum into
    /// the return value so JMH cannot eliminate the loop.
    ///
    /// Result (100M rows): 982.5 ± 30.2 ms/op through the per-leaf accessor path; 178.3 ± 4.1
    /// ms/op with the `DictFilter` lane; 45.5 ± 1.4 ms/op (2026-07-04, 3 forks; ≈ 22×) once
    /// async-profiler + PrintInlining exposed the fold's aggregate read as bimorphic —
    /// `NumericColumns.widenLong` was shared with the match-table pool reads, polluting its
    /// receiver profile — and the fold got its own monomorphic read. The residual gap to
    /// [#fusedFilteredSumDict()] is the genuine per-match fold work (min, max, two counters).
    ///
    /// @return the selected row count plus the sum of `measure` where `category == 7`, folded
    ///         across the whole dataset
    @Benchmark
    public long fusedFilteredAggregateDict() {
        long acc = 0;
        for (Chunk chunk : chunks) {
            FilteredAggregate aggregate = Compute.filteredAggregate(
                    chunk, RowFilter.eq("category", CATEGORY_VALUE), "measure");
            acc += aggregate.selectedRows() + aggregate.sum().longValue();
        }
        return acc;
    }

    /// ADR 0019 baseline, lever 1 — multi-aggregate over one filter: `SUM`/`MIN`(measure) and
    /// `SUM`(plain) under the same single-leaf `category == 7` filter, expressed the only way the
    /// current API allows — one [Compute#filteredAggregate(Chunk, RowFilter, String)] call PER
    /// aggregate column, so the filter column is re-scanned once per aggregate. The transducer
    /// façade folds every aggregate from one scan; this method is the number it must beat.
    ///
    /// Result (2026-07-04, 100M rows, 3 forks): 142.9 ± 6.9 ms/op — ≈ 2× the single-aggregate
    /// lane, the redundant re-scan plus the second aggregate's (random-access `plain`) match reads.
    ///
    /// @return the two folds' selected counts and sums combined, so JMH cannot eliminate either call
    @Benchmark
    public long fusedFilteredAggregateTwoAggregates() {
        long acc = 0;
        for (Chunk chunk : chunks) {
            RowFilter filter = RowFilter.eq("category", CATEGORY_VALUE);
            FilteredAggregate measure = Compute.filteredAggregate(chunk, filter, "measure");
            FilteredAggregate plain = Compute.filteredAggregate(chunk, filter, "plain");
            acc += measure.selectedRows() + measure.sum().longValue()
                    + ((Long) measure.min()).longValue() + plain.sum().longValue();
        }
        return acc;
    }

    /// ADR 0019 baseline, lever 2 — the full façade example: a two-leaf `AND`
    /// (`category == 7 AND price > 500`, ≈ 1/32 selectivity) with two aggregate columns. A
    /// multi-leaf filter declines the dict code-scan lane, so every row pays the per-leaf
    /// [Compute] `RowPredicate` accessor path (dict chunk-lookup for `category`, lazy ALP decode
    /// for `price`), twice — once per aggregate call. The façade's compile step drives the scan
    /// with the dict leaf, tests the residual `price` leaf only on code matches, and feeds both
    /// aggregates from that single pass.
    ///
    /// Result: 2269.1 ± 19.8 ms/op while a multi-leaf `AND` declined the dict lane (2026-07-03);
    /// 201.3 ± 1.6 ms/op (2026-07-04, 3 forks; ≈ 11×) once the kernel's multi-leaf driving scan
    /// landed. The remaining ≈ 2× is the per-aggregate re-scan — the ADR 0019 multi-aggregate
    /// lever.
    ///
    /// @return the two folds' selected counts and sums combined, so JMH cannot eliminate either call
    @Benchmark
    public long fusedFilteredAggregateMulti() {
        long acc = 0;
        for (Chunk chunk : chunks) {
            RowFilter filter = RowFilter.eq("category", CATEGORY_VALUE)
                    .and(RowFilter.gt("price", PRICE_THRESHOLD));
            FilteredAggregate measure = Compute.filteredAggregate(chunk, filter, "measure");
            FilteredAggregate plain = Compute.filteredAggregate(chunk, filter, "plain");
            acc += measure.selectedRows() + measure.sum().longValue() + plain.sum().longValue();
        }
        return acc;
    }

    /// Hand-fused control for [#fusedFilteredSumAlp()]: the obvious developer loop a fused kernel must
    /// match — `for i: if (price.getDouble(i) > 500) acc += measure.getLong(i)` per chunk, with no
    /// off-heap bitmap. Decodes each price and (when selected) each measure through the accessor.
    /// Price and measure chunks are indexed in lockstep.
    ///
    /// @return the sum of `measure` over the rows where `price > 500` across the whole dataset
    @Benchmark
    public long forLoopFilteredSum() {
        long acc = 0;
        for (int k = 0; k < priceChunks.size(); k++) {
            DoubleArray priceArr = priceChunks.get(k);
            LongArray measureArr = measureChunks.get(k);
            long n = priceArr.length();
            for (long i = 0; i < n; i++) {
                if (priceArr.getDouble(i) > PRICE_THRESHOLD) {
                    acc += measureArr.getLong(i);
                }
            }
        }
        return acc;
    }

    /// Naive `price > 500` count baseline: the hand-written count loop over the ALP accessor across
    /// every chunk, with no off-heap bitmap. Decodes each double per element. Returns the count so JMH
    /// cannot eliminate the loop.
    ///
    /// @return the number of rows with `price > 500` over the whole dataset
    @Benchmark
    public long forLoopAlpDouble() {
        long count = 0;
        for (DoubleArray array : priceChunks) {
            long n = array.length();
            for (long i = 0; i < n; i++) {
                if (array.getDouble(i) > PRICE_THRESHOLD) {
                    count++;
                }
            }
        }
        return count;
    }

    /// Naive `measure > base + spread/2` count baseline: the hand-written count loop over the
    /// Frame-of-Reference accessor across every chunk, reconstructing each `offset + ref` long per
    /// element.
    ///
    /// @return the number of rows with `measure > base + spread/2` over the whole dataset
    @Benchmark
    public long forLoopForLong() {
        long count = 0;
        for (LongArray array : measureChunks) {
            long n = array.length();
            for (long i = 0; i < n; i++) {
                if (array.getLong(i) > MEASURE_THRESHOLD) {
                    count++;
                }
            }
        }
        return count;
    }

    /// Naive `category == 7` count baseline: the hand-written count loop over the dictionary accessor
    /// across every chunk, resolving each code through the dictionary per element.
    ///
    /// @return the number of rows with `category == 7` over the whole dataset
    @Benchmark
    public long forLoopDict() {
        long count = 0;
        for (LongArray array : categoryChunks) {
            long n = array.length();
            for (long i = 0; i < n; i++) {
                if (array.getLong(i) == CATEGORY_VALUE) {
                    count++;
                }
            }
        }
        return count;
    }

    /// Encoded-domain `category == 7` count: the ADR-0013 encoded-domain control for a dictionary
    /// equality filter. Resolves the constant to its dictionary code **once per chunk** (a scan of
    /// the tiny value pool), then compares the raw `u8` codes directly — skipping the per-element
    /// `values.getLong(readCode(i))` gather that [#forLoopDict()] pays.
    ///
    /// Result (2026-07-01, 100M rows): this is within noise of [#forLoopDict()] (≈ 520 vs ≈ 530
    /// ms/op), so the value gather is NOT the dict path's bottleneck — both pay the same per-element
    /// accessor cost (≈ 500 ms for a 100 MB code scan vs ≈ 46 ms for a plain 800 MB scan). Kept as a
    /// negative control: encoded-domain value specialization alone does not speed up dict equality;
    /// the lever is a monomorphic / vectorized segment scan of the codes, a separate optimization.
    ///
    /// @return the number of rows with `category == 7` over the whole dataset
    @Benchmark
    public long forLoopDictEncoded() {
        long count = 0;
        for (LongArray array : categoryChunks) {
            // Chunked decode wraps some chunks in an OffsetLongArray slice view; unwrap it to the
            // DictLongArray and carry its offset into the code index (codes[i + offset]).
            LongArray base = array;
            long codeOffset = 0;
            if (base instanceof OffsetLongArray off) {
                codeOffset = off.offset();
                base = off.inner();
            }
            DictLongArray dict = (DictLongArray) base;
            LongArray values = dict.values();
            ByteArray codes = (ByteArray) dict.codes();
            // Resolve category==7 to its code once. The pool holds distinct values, so at most one
            // code matches; an absent value means no row in this chunk matches.
            long match = -1;
            long poolSize = values.length();
            for (long c = 0; c < poolSize; c++) {
                if (values.getLong(c) == CATEGORY_VALUE) {
                    match = c;
                    break;
                }
            }
            long n = array.length();
            if (match < 0) {
                continue;
            }
            byte matchByte = (byte) match;
            for (long i = 0; i < n; i++) {
                if (codes.getByte(i + codeOffset) == matchByte) {
                    count++;
                }
            }
        }
        return count;
    }

    /// Monomorphic segment-scan `category == 7` count: the vectorized-scan candidate that
    /// [#forLoopDictEncoded()]'s negative result points to. Same per-chunk constant-to-code
    /// resolution, but the codes structure is unwrapped ONCE per chunk — the shared-dict codes are a
    /// [ChunkedByteArray], so the accessor path pays a `findChunk` binary search plus a broadcast
    /// guard (`length == elementCount ? i : i % elementCount`, a CMove-evaluated modulo) on EVERY
    /// element. Here the chunk walk is hoisted: each overlapping child's raw [MemorySegment] is
    /// scanned with a bare monomorphic byte compare, the shape superword can vectorize.
    ///
    /// Result (2026-07-02, 100M rows): ≈ 25 ms/op vs ≈ 520–690 ms/op for the accessor variants —
    /// ≈ 20–25×, confirming the accessor dispatch (not the value gather) as the dict bottleneck.
    /// This ceiling is what the `DictFilter` kernel lane reproduces.
    ///
    /// @return the number of rows with `category == 7` over the whole dataset
    @Benchmark
    public long forLoopDictSegment() {
        long count = 0;
        for (LongArray array : categoryChunks) {
            LongArray base = array;
            long codeOffset = 0;
            if (base instanceof OffsetLongArray off) {
                codeOffset = off.offset();
                base = off.inner();
            }
            DictLongArray dict = (DictLongArray) base;
            LongArray values = dict.values();
            long match = -1;
            long poolSize = values.length();
            for (long c = 0; c < poolSize; c++) {
                if (values.getLong(c) == CATEGORY_VALUE) {
                    match = c;
                    break;
                }
            }
            if (match < 0) {
                continue;
            }
            byte matchByte = (byte) match;
            // This benchmark-chunk view covers rows [codeOffset, codeOffset + n) of the shared
            // dict. Hoist the chunk walk: visit only the code children overlapping that range and
            // scan each child's raw segment.
            long start = codeOffset;
            long end = codeOffset + array.length();
            ChunkedByteArray chunked = (ChunkedByteArray) dict.codes();
            ByteArray[] children = chunked.children();
            long[] offsets = chunked.offsets();
            for (int c = 0; c < children.length; c++) {
                long lo = Math.max(start, offsets[c]);
                long hi = Math.min(end, offsets[c + 1]);
                if (lo >= hi) {
                    continue;
                }
                MemorySegment seg = children[c].segmentIfPresent()
                        .orElseThrow(() -> new IllegalStateException("codes child has no backing segment"));
                long segBase = lo - offsets[c];
                long m = hi - lo;
                for (long i = 0; i < m; i++) {
                    if (seg.get(ValueLayout.JAVA_BYTE, segBase + i) == matchByte) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /// Naive `plain > 0` count baseline: the hand-written count loop over the materialized accessor
    /// across every chunk, reading each long straight from the segment per element.
    ///
    /// @return the number of rows with `plain > 0` over the whole dataset
    @Benchmark
    public long forLoopPlainControl() {
        long count = 0;
        for (LongArray array : plainChunks) {
            long n = array.length();
            for (long i = 0; i < n; i++) {
                if (array.getLong(i) > 0L) {
                    count++;
                }
            }
        }
        return count;
    }

    /// Naive `price` running-sum baseline: the hand-written running sum over the ALP accessor across
    /// every chunk, decoding each double per element. Returns the sum so JMH cannot eliminate the
    /// loop.
    ///
    /// @return the sum of all `price` values over the whole dataset
    @Benchmark
    public double forLoopSumAlp() {
        double acc = 0;
        for (DoubleArray array : priceChunks) {
            long n = array.length();
            for (long i = 0; i < n; i++) {
                acc += array.getDouble(i);
            }
        }
        return acc;
    }

    private void write(Path path) throws IOException {
        double[] priceData = new double[CHUNK_ROWS];
        long[] measureData = new long[CHUNK_ROWS];
        long[] categoryData = new long[CHUNK_ROWS];
        long[] plainData = new long[CHUNK_ROWS];

        var rng = new Random(SEED);
        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3))) {
            for (int c = 0; c < CHUNK_COUNT; c++) {
                // Refill the same arrays in place so write-side heap stays ≈ one chunk, not 3.2 GB.
                for (int i = 0; i < CHUNK_ROWS; i++) {
                    priceData[i] = Math.round(rng.nextDouble() * 1000.0 * 100.0) / 100.0;
                    measureData[i] = MEASURE_BASE + rng.nextInt(MEASURE_SPREAD);
                    categoryData[i] = rng.nextInt(CATEGORY_CARDINALITY);
                    plainData[i] = rng.nextLong();
                }
                writer.writeChunk(Map.of(
                        ColumnName.of("price"), priceData,
                        ColumnName.of("measure"), measureData,
                        ColumnName.of("category"), categoryData,
                        ColumnName.of("plain"), plainData));
            }
        }
    }

    /// Verifies a decoded column carries the expected encoded type, looking through the per-chunk
    /// offset slice ([OffsetLongArray] / [OffsetDoubleArray]) the reader inserts when a column
    /// shares one flat layout across aligned chunks (the `category` dictionary does this). Reports
    /// the concrete decoded type and the unwrapped inner type, and fails loudly on a mismatch so the
    /// baseline can never silently measure the wrong path.
    ///
    /// @param array    the decoded column array
    /// @param expected the required encoded array type (after unwrapping offset slices)
    /// @param column   the column name, for diagnostics
    private static void requireType(Array array, Class<? extends Array> expected, String column) {
        Array unwrapped = unwrap(array);
        if (!expected.isInstance(unwrapped)) {
            throw new IllegalStateException("column '" + column + "' decoded to "
                    + describe(array) + ", expected " + expected.getName()
                    + " — the encoded baseline would be measuring the wrong path");
        }
        System.out.printf("  asserted %-8s -> %s%n", column, describe(array));
    }

    private static Array unwrap(Array array) {
        Array current = array;
        while (true) {
            if (current instanceof OffsetLongArray offset) {
                current = offset.inner();
            } else if (current instanceof OffsetDoubleArray offset) {
                current = offset.inner();
            } else {
                return current;
            }
        }
    }

    private static String describe(Array array) {
        Array inner = unwrap(array);
        if (inner == array) {
            return array.getClass().getName();
        }
        return array.getClass().getName() + "(" + inner.getClass().getName() + ")";
    }
}
