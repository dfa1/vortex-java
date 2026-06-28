package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DictLongArray;
import io.github.dfa1.vortex.reader.array.LazyAlpDoubleArray;
import io.github.dfa1.vortex.reader.array.LazyForLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.compute.Compute;
import io.github.dfa1.vortex.reader.compute.Mask;
import io.github.dfa1.vortex.reader.compute.Predicate;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

/// Baseline for the encoded-domain compute-kernel specialisation of ADR 0013.
///
/// The compute kernels ([Compute#filter(Array, Predicate, Arena)] and
/// [Compute#sum(Array, Mask)]) today decode every element through the typed accessor: the
/// generic streaming filter path and the type-specialised, boxing-free reduce lane both read
/// `getLong(i)` / `getDouble(i)` per row, so an ALP or Frame-of-Reference column is fully
/// reconstructed into the value domain before a single comparison or addition runs. The future
/// work compares and reduces directly in the encoded integer domain (ALP residuals, FoR offsets)
/// without decoding. This benchmark pins the CURRENT decode-via-accessor cost so that win is
/// provable: the same `@Benchmark` methods will show the speedup once the specialised kernels land.
///
/// One million rows are written into a single chunk with `WriteOptions.cascading(3)`, so the
/// writer picks real encodings and the four columns decode to:
/// - `price` — `f64` rounded to two decimals, chosen for ALP, decodes to [LazyAlpDoubleArray].
/// - `measure` — `i64` with a large base and a bounded spread, chosen for Frame-of-Reference,
///   decodes to [LazyForLongArray].
/// - `category` — `i64` with sixteen distinct values, chosen for dictionary encoding, decodes to
///   [DictLongArray].
/// - `plain` — `i64` full-range random, with no encoding savings, decodes to
///   [MaterializedLongArray] as an apples baseline for the non-encoded cost.
///
/// `@Setup` asserts each decoded column is the expected encoded type and fails loudly otherwise,
/// so the baseline can never silently measure a plain column.
///
/// Each `filterX`/`sumX` kernel method is paired with a `forLoopX` method holding the true control:
/// the obvious hand-written accessor loop a developer writes WITHOUT the compute layer — no [Mask],
/// no [Compute], no off-heap bitmap, just `getDouble(i)`/`getLong(i)` and a counter. The paired
/// methods share the exact predicate and threshold constant so they cannot drift, giving three
/// reference points:
/// - `forLoopX` — the naive decode-per-element loop, the developer's baseline.
/// - `filterX` — the current kernel, which still decodes through the accessor; the `forLoopX`→
///   `filterX` gap is the kernel's overhead (or benefit) today.
/// - the future encoded-domain specialisation — measured against `forLoopX`, which it must beat by
///   comparing and reducing in the integer domain instead of decoding every element.
///
/// Run: java -jar performance/target/benchmarks.jar ComputeKernelBenchmark
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
})
public class ComputeKernelBenchmark {

    private static final int ROWS = 1_000_000;
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
            COLUMNS,
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
    private Chunk chunk;

    private LazyAlpDoubleArray price;
    private LazyForLongArray measure;
    private DictLongArray category;
    private MaterializedLongArray plain;
    private long rows;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        registry = ReadRegistry.loadAll();
        file = Files.createTempFile("compute-kernel-bench", ".vtx");
        write(file);

        reader = VortexReader.open(file, registry);
        if (reader.chunkCount() != 1) {
            throw new IllegalStateException("expected a single chunk, got " + reader.chunkCount());
        }
        chunk = reader.decodeChunk(0, COLUMNS);
        rows = chunk.rowCount();

        Array priceArr = chunk.column("price");
        Array measureArr = chunk.column("measure");
        Array categoryArr = chunk.column("category");
        Array plainArr = chunk.column("plain");

        System.out.printf("[ComputeKernelBenchmark] decoded column types:%n");
        System.out.printf("  price    -> %s%n", priceArr.getClass().getName());
        System.out.printf("  measure  -> %s%n", measureArr.getClass().getName());
        System.out.printf("  category -> %s%n", categoryArr.getClass().getName());
        System.out.printf("  plain    -> %s%n", plainArr.getClass().getName());

        price = requireType(priceArr, LazyAlpDoubleArray.class, "price");
        measure = requireType(measureArr, LazyForLongArray.class, "measure");
        category = requireType(categoryArr, DictLongArray.class, "category");
        plain = requireType(plainArr, MaterializedLongArray.class, "plain");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (chunk != null) {
            chunk.close();
        }
        if (reader != null) {
            reader.close();
        }
        if (file != null) {
            Files.deleteIfExists(file);
        }
    }

    /// Filters the ALP-encoded `price` column with `price > 500`, decoding every double through the
    /// accessor before the compare. Returns the selected count so the mask cannot be eliminated.
    ///
    /// @return the number of selected rows
    @Benchmark
    public long filterAlpDouble() {
        try (Arena arena = Arena.ofConfined()) {
            Mask result = Compute.filter(price, new Predicate.Gt(PRICE_THRESHOLD), arena);
            return result.trueCount();
        }
    }

    /// Filters the Frame-of-Reference-encoded `measure` column with `measure > base + spread/2`,
    /// reconstructing each `offset + ref` long through the accessor before the compare.
    ///
    /// @return the number of selected rows
    @Benchmark
    public long filterForLong() {
        try (Arena arena = Arena.ofConfined()) {
            Mask result = Compute.filter(measure, new Predicate.Gt(MEASURE_THRESHOLD), arena);
            return result.trueCount();
        }
    }

    /// Filters the dictionary-encoded `category` column with `category == 7`, resolving each code
    /// through the dictionary before the compare.
    ///
    /// @return the number of selected rows
    @Benchmark
    public long filterDict() {
        try (Arena arena = Arena.ofConfined()) {
            Mask result = Compute.filter(category, new Predicate.Eq(CATEGORY_VALUE), arena);
            return result.trueCount();
        }
    }

    /// Control: filters the plain (non-encoded) `plain` column with `plain > 0`, reading each long
    /// straight from the materialised segment. Shows the cost without an encoding to unwind.
    ///
    /// @return the number of selected rows
    @Benchmark
    public long filterPlainControl() {
        try (Arena arena = Arena.ofConfined()) {
            Mask result = Compute.filter(plain, new Predicate.Gt(0L), arena);
            return result.trueCount();
        }
    }

    /// Reduces the ALP-encoded `price` column over an all-selected mask, the boxing-free reduce
    /// lane decoding every double through the accessor before the addition.
    ///
    /// @return the sum of all `price` values
    @Benchmark
    public Number sumAlpDouble() {
        return Compute.sum(price, Mask.allTrue(rows));
    }

    /// Realistic pipeline: filter the ALP-encoded `price` column, then sum the FoR-encoded `measure`
    /// column over the resulting mask. Both stages decode through the accessor today.
    ///
    /// @return the sum of `measure` over the rows where `price > 500`
    @Benchmark
    public Number filterThenSumAlp() {
        try (Arena arena = Arena.ofConfined()) {
            Mask mask = Compute.filter(price, new Predicate.Gt(PRICE_THRESHOLD), arena);
            return Compute.sum(measure, mask);
        }
    }

    /// Naive baseline for [#filterAlpDouble()]: the hand-written `price > 500` count loop over the
    /// ALP accessor, with no [Mask], no [Compute] and no off-heap bitmap. Decodes every double per
    /// element. Returns the count so JMH cannot eliminate the loop.
    ///
    /// @return the number of rows with `price > 500`
    @Benchmark
    public long forLoopAlpDouble() {
        LazyAlpDoubleArray array = price;
        long n = array.length();
        long count = 0;
        for (long i = 0; i < n; i++) {
            if (array.getDouble(i) > PRICE_THRESHOLD) {
                count++;
            }
        }
        return count;
    }

    /// Naive baseline for [#filterForLong()]: the hand-written `measure > base + spread/2` count loop
    /// over the Frame-of-Reference accessor, reconstructing each `offset + ref` long per element.
    ///
    /// @return the number of rows with `measure > base + spread/2`
    @Benchmark
    public long forLoopForLong() {
        LazyForLongArray array = measure;
        long n = array.length();
        long count = 0;
        for (long i = 0; i < n; i++) {
            if (array.getLong(i) > MEASURE_THRESHOLD) {
                count++;
            }
        }
        return count;
    }

    /// Naive baseline for [#filterDict()]: the hand-written `category == 7` count loop over the
    /// dictionary accessor, resolving each code through the dictionary per element.
    ///
    /// @return the number of rows with `category == 7`
    @Benchmark
    public long forLoopDict() {
        DictLongArray array = category;
        long n = array.length();
        long count = 0;
        for (long i = 0; i < n; i++) {
            if (array.getLong(i) == CATEGORY_VALUE) {
                count++;
            }
        }
        return count;
    }

    /// Naive baseline for [#filterPlainControl()]: the hand-written `plain > 0` count loop over the
    /// materialised accessor, reading each long straight from the segment per element.
    ///
    /// @return the number of rows with `plain > 0`
    @Benchmark
    public long forLoopPlainControl() {
        MaterializedLongArray array = plain;
        long n = array.length();
        long count = 0;
        for (long i = 0; i < n; i++) {
            if (array.getLong(i) > 0L) {
                count++;
            }
        }
        return count;
    }

    /// Naive baseline for [#sumAlpDouble()]: the hand-written running sum over the ALP accessor,
    /// decoding every double per element. Returns the sum so JMH cannot eliminate the loop.
    ///
    /// @return the sum of all `price` values
    @Benchmark
    public double forLoopSumAlp() {
        LazyAlpDoubleArray array = price;
        long n = array.length();
        double acc = 0;
        for (long i = 0; i < n; i++) {
            acc += array.getDouble(i);
        }
        return acc;
    }

    private void write(Path path) throws IOException {
        double[] priceData = new double[ROWS];
        long[] measureData = new long[ROWS];
        long[] categoryData = new long[ROWS];
        long[] plainData = new long[ROWS];

        var rng = new Random(SEED);
        for (int i = 0; i < ROWS; i++) {
            priceData[i] = Math.round(rng.nextDouble() * 1000.0 * 100.0) / 100.0;
            measureData[i] = MEASURE_BASE + rng.nextInt(MEASURE_SPREAD);
            categoryData[i] = rng.nextInt(CATEGORY_CARDINALITY);
            plainData[i] = rng.nextLong();
        }

        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3))) {
            writer.writeChunk(Map.of(
                    "price", priceData,
                    "measure", measureData,
                    "category", categoryData,
                    "plain", plainData));
        }
    }

    private static <T extends Array> T requireType(Array array, Class<T> expected, String column) {
        if (!expected.isInstance(array)) {
            throw new IllegalStateException("column '" + column + "' decoded to "
                    + array.getClass().getName() + ", expected " + expected.getName()
                    + " — the encoded baseline would be measuring the wrong path");
        }
        return expected.cast(array);
    }
}
