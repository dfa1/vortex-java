package io.github.dfa1.vortex.performance;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanResult;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/// Pco decoder throughput benchmark: Java reader vs JNI reader on the same Pco-encoded file.
///
/// <p>Uses {@code tpch_lineitem.compact.vortex} from the S3 fixture set (Rust-encoded with
/// Classic+Consecutive pco), cached at {@code /tmp/pco-fixtures/}. Benchmarks the {@code
/// l_orderkey} column (I64, Pco Classic+Consecutive delta).
///
/// <p>Run:
/// <pre>
///   ./bench PcoDecodeBenchmark.javaDecodeI64
///   ./bench PcoDecodeBenchmark.jniDecodeI64
/// </pre>
///
/// <p>To skip S3 download with a pre-existing file:
/// <pre>
///   java -Dvortex.bench.pco.fixture=/path/to/file.vtx -jar benchmarks.jar PcoDecodeBenchmark
/// </pre>
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgsAppend = {
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
})
public class PcoDecodeBenchmark {

    private static final String S3_BASE = "https://vortex-compat-fixtures.s3.amazonaws.com/v0.72.0/arrays/";
    private static final String FIXTURE = "tpch_lineitem.compact.vortex";
    private static final String COLUMN = "l_orderkey";

    private static final Session SESSION = Session.create();

    static {
        NativeLoader.loadJni();
    }

    private Path benchFile;
    private EncodingRegistry registry;
    private BufferAllocator allocator;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        registry = EncodingRegistry.loadAll();
        allocator = ArrowAllocation.rootAllocator();

        String externalFile = System.getProperty("vortex.bench.pco.fixture");
        if (externalFile != null && !externalFile.isEmpty()) {
            benchFile = Path.of(externalFile);
            System.out.printf("[PcoDecodeBenchmark] using external file: %s%n", benchFile);
        } else {
            benchFile = downloadIfMissing(FIXTURE);
            System.out.printf("[PcoDecodeBenchmark] fixture: %s (%.1f KB)%n",
                    benchFile, Files.size(benchFile) / 1024.0);
        }
    }

    @TearDown(Level.Trial)
    public void cleanup() {
        // fixture is cached, nothing to delete
    }

    /// Java reader: decode Pco I64 column (Classic+Consecutive delta), sum all values.
    @Benchmark
    public long javaDecodeI64() throws IOException {
        long sum = 0L;
        try (VortexReader vf = VortexReader.open(benchFile, registry)) {
            var iter = vf.scan(io.github.dfa1.vortex.scan.ScanOptions.columns(COLUMN));
            while (iter.hasNext()) {
                ScanResult r = iter.next();
                LongArray col = r.column(COLUMN);
                sum += col.fold(0L, Long::sum);
            }
        }
        return sum;
    }

    /// JNI (Rust) reader: decode Pco I64 column, sum all values.
    @Benchmark
    public long jniDecodeI64() throws IOException {
        String uri = benchFile.toAbsolutePath().toUri().toString();
        var opts = ScanOptions.builder()
                .projection(Expression.select(new String[]{COLUMN}, Expression.root()))
                .build();

        long sum = 0L;
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(allocator)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    BigIntVector vec = (BigIntVector) root.getVector(COLUMN);
                    for (int i = 0; i < root.getRowCount(); i++) {
                        sum += vec.get(i);
                    }
                }
            }
        }
        return sum;
    }

    private static Path downloadIfMissing(String name) throws IOException {
        Path cache = Path.of("/tmp/pco-fixtures", name);
        if (Files.exists(cache)) {
            return cache;
        }
        Files.createDirectories(cache.getParent());
        System.out.printf("[PcoDecodeBenchmark] downloading %s from S3...%n", name);
        try (var in = URI.create(S3_BASE + name).toURL().openStream()) {
            Files.copy(in, cache, StandardCopyOption.REPLACE_EXISTING);
        }
        return cache;
    }
}
