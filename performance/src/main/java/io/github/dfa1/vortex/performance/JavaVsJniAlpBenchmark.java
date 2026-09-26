package io.github.dfa1.vortex.performance;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.FloatingPointPrecision;
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
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// ALP benchmark: Java writer/reader vs JNI (Rust) writer/reader on a single F64 column of
/// bounded-precision decimal values that the cost-based dispatch routes through `vortex.alp` —
/// the per-encoding sibling of [JavaVsJniFsstBenchmark] / [JavaVsJniDictBenchmark].
///
/// Corpus: 1 M prices in `[50, 150)` rounded to 2 decimal digits — ALP's target shape (few
/// significant decimal digits, so every value re-encodes losslessly as a scaled integer).
/// `@Setup` verifies via the inspector that `vortex.alp` was actually selected, so a future
/// dispatch change can't silently turn this into a no-op benchmark of some other encoding.
///
/// Run: java -jar performance/target/benchmarks.jar JavaVsJniAlpBenchmark
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
public class JavaVsJniAlpBenchmark {

    private static final int TOTAL_ROWS = 1_000_000;
    private static final int BATCH_SIZE = 50_000;
    private static final int NUM_BATCHES = TOTAL_ROWS / BATCH_SIZE;

    private static final ColumnName PRICE = ColumnName.of("price");
    private static final DType.Struct JAVA_SCHEMA = new DType.Struct(
            List.of(PRICE), List.of(DType.F64), false);
    private static final ArrowType F64_TYPE = new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    private static final Schema JNI_SCHEMA = new Schema(List.of(
            Field.notNullable("price", F64_TYPE)));

    private static final Session SESSION = Session.create();

    static {
        NativeLoader.loadJni();
    }

    // Pre-generated per-batch corpus — filled once in @Setup, reused across invocations.
    private double[][] batchPrices;

    private Path javaWriteFile;
    private Path jniWriteFile;
    private Path javaReadFile;
    private Path jniReadFile;
    private ReadRegistry registry;
    private BufferAllocator allocator;

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @Setup(Level.Trial)
    public void setup() throws IOException {
        registry = ReadRegistry.loadAll();
        allocator = ArrowAllocation.rootAllocator();

        javaWriteFile = Files.createTempFile("alp-java-write", ".vtx");
        jniWriteFile = Files.createTempFile("alp-jni-write", ".vtx");
        javaReadFile = Files.createTempFile("alp-java-read", ".vtx");
        jniReadFile = Files.createTempFile("alp-jni-read", ".vtx");

        batchPrices = new double[NUM_BATCHES][BATCH_SIZE];
        var rng = new Random(42L);
        for (int b = 0; b < NUM_BATCHES; b++) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                batchPrices[b][i] = round2(50.0 + rng.nextDouble() * 100.0);
            }
        }

        // Pre-write the read-benchmark inputs once (not measured), one per implementation.
        writeJava(javaReadFile);
        writeJni(jniReadFile);

        verifyAlpSelected(javaReadFile);

        System.out.printf("[JavaVsJniAlpBenchmark] corpus pre-generated: %d rows in %d batches; "
                        + "java read file=%.1f KB, jni read file=%.1f KB%n",
                TOTAL_ROWS, NUM_BATCHES,
                Files.size(javaReadFile) / 1024.0, Files.size(jniReadFile) / 1024.0);
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException {
        Files.deleteIfExists(javaWriteFile);
        Files.deleteIfExists(jniWriteFile);
        Files.deleteIfExists(javaReadFile);
        Files.deleteIfExists(jniReadFile);
    }

    /// Java write: encode and write 1 M prices via Java VortexWriter.
    @Benchmark
    public long javaAlpEncode() throws IOException {
        writeJava(javaWriteFile);
        return Files.size(javaWriteFile);
    }

    /// JNI write: encode and write 1 M prices via Rust VortexWriter.
    @Benchmark
    public long jniAlpEncode() throws IOException {
        writeJni(jniWriteFile);
        return Files.size(jniWriteFile);
    }

    /// Java read: scan the ALP column, sum decoded values via fold.
    @Benchmark
    public double javaAlpDecode() throws IOException {
        double sum = 0.0;
        try (VortexReader vf = VortexReader.open(javaReadFile, registry);
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("price"))) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    DoubleArray price = c.column("price");
                    sum += price.fold(0.0, Double::sum);
                }
            }
        }
        return sum;
    }

    /// JNI read: scan the ALP column, sum decoded values.
    @Benchmark
    public double jniAlpDecode() throws IOException {
        String uri = jniReadFile.toAbsolutePath().toUri().toString();
        var opts = ScanOptions.builder()
                           .projection(Expression.select(new String[]{"price"}, Expression.root()))
                           .build();

        double sum = 0.0;
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(allocator)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    Float8Vector priceVec = (Float8Vector) root.getVector("price");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        sum += priceVec.get(i);
                    }
                }
            }
        }
        return sum;
    }

    // ── Corpus + writers ────────────────────────────────────────────────────────

    private void writeJava(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(ch, JAVA_SCHEMA, WriteOptions.defaults())) {
            for (int b = 0; b < NUM_BATCHES; b++) {
                writer.writeChunk(Map.of(PRICE, batchPrices[b]));
            }
        }
    }

    private void writeJni(Path path) throws IOException {
        String uri = path.toAbsolutePath().toUri().toString();
        try (dev.vortex.api.VortexWriter writer = dev.vortex.api.VortexWriter.builder(
                SESSION, uri, JNI_SCHEMA, allocator).build()) {
            for (int b = 0; b < NUM_BATCHES; b++) {
                flushJni(writer, b);
            }
        }
    }

    private void flushJni(dev.vortex.api.VortexWriter writer, int b) throws IOException {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(JNI_SCHEMA, allocator)) {
            Float8Vector priceVec = (Float8Vector) root.getVector("price");
            int n = batchPrices[b].length;
            priceVec.allocateNew(n);
            for (int i = 0; i < n; i++) {
                priceVec.set(i, batchPrices[b][i]);
            }
            root.setRowCount(n);

            try (ArrowArray arr = ArrowArray.allocateNew(allocator);
                 ArrowSchema schema = ArrowSchema.allocateNew(allocator)) {
                Data.exportVectorSchemaRoot(allocator, root, null, arr, schema);
                writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
            }
        }
    }

    /// Fails loudly if the Java writer did not route the column through `vortex.alp`, so a
    /// future dispatch change can't silently turn the decode benchmarks into a no-op measurement
    /// of some other encoding.
    private void verifyAlpSelected(Path path) throws IOException {
        try (VortexReader vf = VortexReader.open(path, registry)) {
            InspectorTree tree = InspectorTree.build(vf);
            if (!tree.usedEncodings().contains("vortex.alp")) {
                throw new VortexException("expected vortex.alp to be selected, but used encodings were "
                        + tree.usedEncodings());
            }
        }
    }
}
