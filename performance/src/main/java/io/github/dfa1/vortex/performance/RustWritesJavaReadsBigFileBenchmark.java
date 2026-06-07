package io.github.dfa1.vortex.performance;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanResult;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
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
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Big-file scan benchmark exercising the TODO #12 `SegmentSpec.length` = long fix.
///
/// <p>Setup: JNI writer produces a >2 GB Vortex file with 4 incompressible I64 columns
/// (random longs defeat bit-packing / FoR so the segments stay large). Measurement: pure-Java
/// `VortexReader` scans every column and sums the first column.
///
/// <p>The trial setup writes ~3 GB to disk and takes tens of seconds, so this benchmark is
/// intended for occasional runs (e.g. before tagging a release). To skip the JNI write, pass
/// {@code -Dvortex.bench.bigfile=/path/to/existing.vtx} — useful when iterating on the read
/// path against the same fixture.
///
/// <p>Run: {@code java -jar performance/target/benchmarks.jar RustWritesJavaReadsBigFileBenchmark.javaScan}
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 3, time = 10)
@Fork(value = 1, jvmArgsAppend = {
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
})
public class RustWritesJavaReadsBigFileBenchmark {

    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final long TARGET_BYTES = 3L * 1024 * 1024 * 1024; // ~3 GB
    private static final int BATCH_SIZE = 1_000_000; // 1 M rows/batch → ~32 MB raw per col per batch
    private static final int COLUMNS = 4;
    private static final long BYTES_PER_ROW = (long) COLUMNS * Long.BYTES;
    private static final long TOTAL_ROWS = TARGET_BYTES / BYTES_PER_ROW;

    private static final ArrowType I64_TYPE = new ArrowType.Int(64, true);
    private static final Schema JNI_SCHEMA = new Schema(List.of(
            Field.notNullable("c0", I64_TYPE),
            Field.notNullable("c1", I64_TYPE),
            Field.notNullable("c2", I64_TYPE),
            Field.notNullable("c3", I64_TYPE)
    ));
    private static final Session SESSION = Session.create();

    static {
        NativeLoader.loadJni();
    }

    private Path benchFile;
    private boolean ownFile;
    private EncodingRegistry registry;
    private BufferAllocator allocator;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        registry = EncodingRegistry.loadAll();
        allocator = ArrowAllocation.rootAllocator();

        String externalFile = System.getProperty("vortex.bench.bigfile");
        if (externalFile != null && !externalFile.isEmpty()) {
            benchFile = Path.of(externalFile);
            ownFile = false;
            System.out.printf("[BigFileBenchmark] using external file: %s (%.2f GB)%n",
                    benchFile, Files.size(benchFile) / (double) (1L << 30));
        } else {
            benchFile = Files.createTempFile("vortex-bigfile-bench", ".vtx");
            ownFile = true;
            System.out.printf("[BigFileBenchmark] writing %d rows × %d I64 cols (~%.2f GB)...%n",
                    TOTAL_ROWS, COLUMNS, TARGET_BYTES / (double) (1L << 30));
            writeJni(benchFile);
            System.out.printf("[BigFileBenchmark] file size: %.2f GB%n",
                    Files.size(benchFile) / (double) (1L << 30));
        }

        long jniSum = scanJni();
        long javaSum = scanJava();
        if (jniSum != javaSum) {
            throw new AssertionError(
                    "Big-file correctness check failed: JNI c0 sum=" + jniSum + " Java c0 sum=" + javaSum);
        }
        System.out.printf("[BigFileBenchmark] correctness OK: c0 sum=%d%n", javaSum);
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException {
        if (ownFile) {
            Files.deleteIfExists(benchFile);
        }
    }

    /// JNI reader scans the first column ("c0") via Arrow C Data Interface and sums every value.
    @Benchmark
    public long jniScan() throws IOException {
        return scanJni();
    }

    /// Java reader scans the first column and sums every value. Sum prevents the JIT
    /// from eliding the decode loop.
    @Benchmark
    public long javaScan() throws IOException {
        return scanJava();
    }

    private long scanJni() throws IOException {
        String uri = benchFile.toAbsolutePath().toUri().toString();
        var opts = ScanOptions.builder()
                           .projection(Expression.select(new String[]{"c0"}, Expression.root()))
                           .build();

        long sum = 0L;
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(allocator)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    BigIntVector v = (BigIntVector) root.getVector("c0");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        sum += v.get(i);
                    }
                }
            }
        }
        return sum;
    }

    private long scanJava() throws IOException {
        long sum = 0L;
        try (VortexReader vf = VortexReader.open(benchFile, registry)) {
            var iter = vf.scan(io.github.dfa1.vortex.scan.ScanOptions.columns("c0"));
            while (iter.hasNext()) {
                ScanResult r = iter.next();
                Array arr = r.columns().get("c0");
                MemorySegment buf = ArraySegments.of(arr);
                long count = buf.byteSize() / Long.BYTES;
                for (long i = 0; i < count; i++) {
                    sum += buf.getAtIndex(LE_LONG, i);
                }
            }
        }
        return sum;
    }

    private void writeJni(Path path) throws IOException {
        String uri = path.toAbsolutePath().toUri().toString();
        var rng = new Random(42L);
        long[][] cols = new long[COLUMNS][BATCH_SIZE];
        long rowsLeft = TOTAL_ROWS;

        try (dev.vortex.api.VortexWriter writer = dev.vortex.api.VortexWriter.create(
                SESSION, uri, JNI_SCHEMA, new HashMap<>(), allocator)) {
            while (rowsLeft > 0) {
                int n = (int) Math.min(rowsLeft, BATCH_SIZE);
                for (int c = 0; c < COLUMNS; c++) {
                    for (int i = 0; i < n; i++) {
                        // Random data defeats bit-packing / FoR so the segments stay large
                        // and the resulting file exercises the >2 GB read path.
                        cols[c][i] = rng.nextLong();
                    }
                }
                flushBatch(writer, cols, n);
                rowsLeft -= n;
            }
        }
    }

    private void flushBatch(dev.vortex.api.VortexWriter writer, long[][] cols, int n) throws IOException {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(JNI_SCHEMA, allocator)) {
            BigIntVector[] vecs = new BigIntVector[COLUMNS];
            for (int c = 0; c < COLUMNS; c++) {
                vecs[c] = (BigIntVector) root.getVector("c" + c);
                vecs[c].allocateNew(n);
            }
            for (int c = 0; c < COLUMNS; c++) {
                for (int i = 0; i < n; i++) {
                    vecs[c].setSafe(i, cols[c][i]);
                }
            }
            root.setRowCount(n);

            try (ArrowArray arr = ArrowArray.allocateNew(allocator);
                 ArrowSchema schema = ArrowSchema.allocateNew(allocator)) {
                Data.exportVectorSchemaRoot(allocator, root, null, arr, schema);
                writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
            }
        }
    }
}
