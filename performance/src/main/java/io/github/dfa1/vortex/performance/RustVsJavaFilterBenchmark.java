package io.github.dfa1.vortex.performance;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
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
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/// Filter-pushdown benchmark: JNI reader vs Java reader on the "close" column
/// with a `close > threshold` predicate at varying selectivity.
///
/// Phase 0 instrument for ADR 0010 (lazy decode). The threshold is chosen at
/// setup time so each {@link #selectivity} value selects approximately that
/// fraction of rows. Both readers report the sum of surviving close values so
/// the JVM cannot elide the per-row work.
///
/// JNI receives a native filter Expression and only materializes matching
/// rows. Java today must materialize every row in the chunk and check the
/// predicate in user code — this benchmark exposes that gap.
///
/// Requires a pre-existing OHLC file:
/// `-Dvortex.bench.ohlc=/path/to/file.vtx`. Generate one by running
/// {@link RustVsJavaReadBenchmark} first with the same `vortex.bench.ohlc`
/// pointing at the path you want (the read benchmark will write it lazily if
/// the file does not exist yet).
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(value = 1, jvmArgsAppend = {
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
})
public class RustVsJavaFilterBenchmark {

    private static final Session SESSION = Session.create();

    static {
        NativeLoader.loadJni();
    }

    /// Target fraction of rows that should satisfy `close > threshold`.
    /// The threshold is computed in {@link #setup()} from a sample of the
    /// "close" column; the realised selectivity is reported in the setup log
    /// and may differ slightly from this value on small samples.
    @Param({"0.001", "0.01", "0.1", "1.0"})
    public double selectivity;

    private Path benchFile;
    private ReadRegistry registry;
    private BufferAllocator allocator;
    private double threshold;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        registry = ReadRegistry.loadAll();
        allocator = ArrowAllocation.rootAllocator();

        String externalFile = System.getProperty("vortex.bench.ohlc");
        if (externalFile == null || externalFile.isEmpty()) {
            throw new IllegalStateException(
                    "RustVsJavaFilterBenchmark requires -Dvortex.bench.ohlc=<file> "
                            + "(reuse the file produced by RustVsJavaReadBenchmark)");
        }
        benchFile = Path.of(externalFile);
        if (!Files.isReadable(benchFile)) {
            throw new IllegalStateException("vortex.bench.ohlc not readable: " + benchFile);
        }

        threshold = computeThreshold(selectivity);
        System.out.printf("[RustVsJavaFilterBenchmark] selectivity=%.4f → threshold=%.4f%n",
                selectivity, threshold);
    }

    /// JNI read: native filter pushdown via Expression; sum surviving "close".
    @Benchmark
    public double jniFilterClose() throws IOException {
        String uri = benchFile.toAbsolutePath().toUri().toString();
        Expression closeCol = Expression.column("close");
        Expression filter = Expression.binary(
                Expression.BinaryOp.GT, closeCol, Expression.literal(threshold));
        Expression projection = Expression.select(new String[]{"close"}, Expression.root());
        var opts = ScanOptions.builder()
                .filter(filter)
                .projection(projection)
                .build();

        double sum = 0.0;
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(allocator)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    Float8Vector vec = (Float8Vector) root.getVector("close");
                    int n = root.getRowCount();
                    for (int i = 0; i < n; i++) {
                        sum += vec.get(i);
                    }
                }
            }
        }
        return sum;
    }

    /// Java read: zone-map prune via RowFilter, then per-row predicate in
    /// user code over the fully-decoded chunk. Materialization happens for
    /// every row in non-pruned chunks regardless of the filter outcome.
    @Benchmark
    public double javaFilterClose() throws IOException {
        RowFilter filter = RowFilter.gt("close", threshold);
        var opts = io.github.dfa1.vortex.reader.ScanOptions.columns("close")
                .withFilter(filter);

        double sum = 0.0;
        try (VortexReader vf = VortexReader.open(benchFile, registry);
             var iter = vf.scan(opts)) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    DoubleArray close = c.column("close");
                    long n = close.length();
                    for (long i = 0; i < n; i++) {
                        double v = close.getDouble(i);
                        if (v > threshold) {
                            sum += v;
                        }
                    }
                }
            }
        }
        return sum;
    }

    /// Sample every 1000th "close" value, sort, return the value at the
    /// `(1 - selectivity)` quantile so `close > threshold` selects
    /// roughly `selectivity` of all rows.
    private double computeThreshold(double targetSelectivity) throws IOException {
        List<Double> sample = new ArrayList<>(100_000);
        try (VortexReader vf = VortexReader.open(benchFile, registry);
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("close"))) {
            int stride = 1000;
            int counter = 0;
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    DoubleArray close = c.column("close");
                    long n = close.length();
                    for (long i = 0; i < n; i++) {
                        if (counter++ % stride == 0) {
                            sample.add(close.getDouble(i));
                        }
                    }
                }
            }
        }
        Collections.sort(sample);
        int idx = (int) Math.min(sample.size() - 1L,
                Math.round((1.0 - targetSelectivity) * sample.size()));
        return sample.get(idx);
    }
}
