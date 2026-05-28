package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.encoding.CodecRegistry;
import io.github.dfa1.vortex.io.VortexFile;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.scan.ScanResult;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
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
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/// Large-file read benchmark (~512 MB) for throughput measurement.
///
/// By default generates a file with the Java writer. To benchmark against
/// a pre-existing file (e.g. Rust-written), set the system property:
///
///   java -Dvortex.bench.file=/path/to/file.vtx -jar target/benchmarks.jar LargeReadBenchmark
///
/// Schema: id(I64) + value(F64) + category(I32) + ts(I64) = 28 bytes/row.
/// Default: 18,000,000 rows x 100 chunks = ~504 MB uncompressed.
///
/// Run: java -jar performance/target/benchmarks.jar LargeReadBenchmark
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgsAppend = {
    "--enable-preview",
    "--add-modules", "java.base"
})
public class LargeReadBenchmark {

    private static final int TOTAL_ROWS   = 18_000_000;
    private static final int CHUNK_COUNT  = 100;
    private static final int CHUNK_ROWS   = TOTAL_ROWS / CHUNK_COUNT;

    private static final DType.Struct SCHEMA = new DType.Struct(
        List.of("id", "value", "category", "ts"),
        List.of(
            new DType.Primitive(PType.I64, false),
            new DType.Primitive(PType.F64, false),
            new DType.Primitive(PType.I32, false),
            new DType.Primitive(PType.I64, false)
        ),
        false);

    private Path            benchFile;
    private boolean         ownFile;     // true = we generated it, we delete it
    private CodecRegistry registry;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        registry = CodecRegistry.loadAll();

        String externalFile = System.getProperty("vortex.bench.file");
        if (externalFile != null && !externalFile.isEmpty()) {
            benchFile = Path.of(externalFile);
            ownFile   = false;
            System.out.printf("[LargeReadBenchmark] using external file: %s%n", benchFile);
        } else {
            benchFile = Files.createTempFile("vortex-large-bench", ".vtx");
            ownFile   = true;
            System.out.printf("[LargeReadBenchmark] generating %d rows in %d chunks (~%d MB)...%n",
                TOTAL_ROWS, CHUNK_COUNT, estimatedMB());
            generateFile(benchFile);
            System.out.printf("[LargeReadBenchmark] file size: %.1f MB%n",
                Files.size(benchFile) / 1_048_576.0);
        }
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException {
        if (ownFile) {
            Files.deleteIfExists(benchFile);
        }
    }

    /// Scan all columns — measures full decode throughput.
    @Benchmark
    public long scanAll() throws IOException {
        return scan(ScanOptions.all());
    }

    /// Scan only the id column — measures projection + single-column decode.
    @Benchmark
    public long scanId() throws IOException {
        return scan(ScanOptions.columns("id"));
    }

    /// Sum all id values — forces actual MemorySegment reads (page faults) across all chunks.
    /// This is the meaningful metric for JNI comparison: real decode throughput, not just
    /// scan infrastructure overhead.
    @Benchmark
    public long checksumId() throws IOException {
        long sum = 0;
        var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        try (VortexFile vf = VortexFile.open(benchFile, registry)) {
            var iter = vf.scan(ScanOptions.columns("id"));
            while (iter.hasNext()) {
                ScanResult r = iter.next();
                Array arr = r.columns().get("id");
                var buf = arr.buffer(0);
                long len = arr.length();
                for (long j = 0; j < len; j++) {
                    sum += buf.get(layout, j * Long.BYTES);
                }
            }
        }
        return sum;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long scan(ScanOptions opts) throws IOException {
        long rows = 0;
        try (VortexFile vf = VortexFile.open(benchFile, registry)) {
            var iter = vf.scan(opts);
            while (iter.hasNext()) {
                ScanResult r = iter.next();
                rows += r.rowCount();
            }
        }
        return rows;
    }

    private static void generateFile(Path path) throws IOException {
        long[]   ids        = new long[CHUNK_ROWS];
        double[] values     = new double[CHUNK_ROWS];
        int[]    categories = new int[CHUNK_ROWS];
        long[]   ts         = new long[CHUNK_ROWS];

        for (int i = 0; i < CHUNK_ROWS; i++) {
            ids[i]        = i;
            values[i]     = i * 1.234567;
            categories[i] = i % 256;
            ts[i]         = 1_700_000_000L + i;
        }

        try (var ch     = FileChannel.open(path,
                              StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                              StandardOpenOption.TRUNCATE_EXISTING);
             var writer = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            for (int c = 0; c < CHUNK_COUNT; c++) {
                writer.writeChunk(Map.of(
                    "id",       ids,
                    "value",    values,
                    "category", categories,
                    "ts",       ts));
            }
        }
    }

    private static long estimatedMB() {
        // 8 + 8 + 4 + 8 = 28 bytes/row uncompressed
        return (long) TOTAL_ROWS * 28 / 1_048_576;
    }
}
