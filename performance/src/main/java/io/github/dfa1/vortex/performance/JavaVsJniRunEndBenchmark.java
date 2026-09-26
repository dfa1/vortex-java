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
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
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
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/// RunEnd benchmark: Java writer/reader vs JNI (Rust) writer/reader on a single I64 column of
/// long, locally-constant runs that the cost-based dispatch routes through `vortex.runend` — the
/// per-encoding sibling of [JavaVsJniFsstBenchmark] / [JavaVsJniDictBenchmark] /
/// [JavaVsJniAlpBenchmark] / [JavaVsJniBitPackedBenchmark].
///
/// Corpus: 1 M rows, a new level every 2 000 rows (`row / 2000`) — 500 runs total, so storing
/// `(value, run_length)` pairs is far cheaper than one value per row. `@Setup` verifies via the
/// inspector that `vortex.runend` was actually selected, so a future dispatch change can't
/// silently turn this into a no-op benchmark of some other encoding.
///
/// Run: java -jar performance/target/benchmarks.jar JavaVsJniRunEndBenchmark
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
public class JavaVsJniRunEndBenchmark {

    private static final int TOTAL_ROWS = 1_000_000;
    private static final int BATCH_SIZE = 50_000;
    private static final int NUM_BATCHES = TOTAL_ROWS / BATCH_SIZE;
    private static final int RUN_LENGTH = 2_000;

    private static final ColumnName LEVEL = ColumnName.of("level");
    private static final DType.Struct JAVA_SCHEMA = new DType.Struct(
            List.of(LEVEL), List.of(DType.I64), false);
    private static final Schema JNI_SCHEMA = new Schema(List.of(
            Field.notNullable("level", new ArrowType.Int(64, true))));

    private static final Session SESSION = Session.create();

    static {
        NativeLoader.loadJni();
    }

    // Pre-generated per-batch corpus — filled once in @Setup, reused across invocations.
    private long[][] batchLevels;

    private Path javaWriteFile;
    private Path jniWriteFile;
    private Path javaReadFile;
    private Path jniReadFile;
    private ReadRegistry registry;
    private BufferAllocator allocator;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        registry = ReadRegistry.loadAll();
        allocator = ArrowAllocation.rootAllocator();

        javaWriteFile = Files.createTempFile("runend-java-write", ".vtx");
        jniWriteFile = Files.createTempFile("runend-jni-write", ".vtx");
        javaReadFile = Files.createTempFile("runend-java-read", ".vtx");
        jniReadFile = Files.createTempFile("runend-jni-read", ".vtx");

        batchLevels = new long[NUM_BATCHES][BATCH_SIZE];
        for (int b = 0; b < NUM_BATCHES; b++) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                int row = b * BATCH_SIZE + i;
                batchLevels[b][i] = row / RUN_LENGTH;
            }
        }

        // Pre-write the read-benchmark inputs once (not measured), one per implementation.
        writeJava(javaReadFile);
        writeJni(jniReadFile);

        verifyRunEndSelected(javaReadFile);

        System.out.printf("[JavaVsJniRunEndBenchmark] corpus pre-generated: %d rows in %d batches; "
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

    /// Java write: encode and write 1 M long-run levels via Java VortexWriter.
    @Benchmark
    public long javaRunEndEncode() throws IOException {
        writeJava(javaWriteFile);
        return Files.size(javaWriteFile);
    }

    /// JNI write: encode and write 1 M long-run levels via Rust VortexWriter.
    @Benchmark
    public long jniRunEndEncode() throws IOException {
        writeJni(jniWriteFile);
        return Files.size(jniWriteFile);
    }

    /// Java read: scan the RunEnd column, sum decoded values via fold.
    @Benchmark
    public long javaRunEndDecode() throws IOException {
        long sum = 0L;
        try (VortexReader vf = VortexReader.open(javaReadFile, registry);
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("level"))) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    LongArray level = c.column("level");
                    sum += level.fold(0L, Long::sum);
                }
            }
        }
        return sum;
    }

    /// JNI read: scan the RunEnd column, sum decoded values.
    @Benchmark
    public long jniRunEndDecode() throws IOException {
        String uri = jniReadFile.toAbsolutePath().toUri().toString();
        var opts = ScanOptions.builder()
                           .projection(Expression.select(new String[]{"level"}, Expression.root()))
                           .build();

        long sum = 0L;
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(allocator)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    BigIntVector levelVec = (BigIntVector) root.getVector("level");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        sum += levelVec.get(i);
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
             VortexWriter writer = VortexWriter.create(ch, JAVA_SCHEMA, WriteOptions.cascading(3))) {
            for (int b = 0; b < NUM_BATCHES; b++) {
                writer.writeChunk(Map.of(LEVEL, batchLevels[b]));
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
            BigIntVector levelVec = (BigIntVector) root.getVector("level");
            int n = batchLevels[b].length;
            levelVec.allocateNew(n);
            for (int i = 0; i < n; i++) {
                levelVec.set(i, batchLevels[b][i]);
            }
            root.setRowCount(n);

            try (ArrowArray arr = ArrowArray.allocateNew(allocator);
                 ArrowSchema schema = ArrowSchema.allocateNew(allocator)) {
                Data.exportVectorSchemaRoot(allocator, root, null, arr, schema);
                writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
            }
        }
    }

    /// Fails loudly if the Java writer did not route the column through `vortex.runend`, so a
    /// future dispatch change can't silently turn the decode benchmarks into a no-op measurement
    /// of some other encoding.
    private void verifyRunEndSelected(Path path) throws IOException {
        try (VortexReader vf = VortexReader.open(path, registry)) {
            InspectorTree tree = InspectorTree.build(vf);
            if (!tree.usedEncodings().contains("vortex.runend")) {
                throw new VortexException("expected vortex.runend to be selected, but used encodings were "
                        + tree.usedEncodings());
            }
        }
    }
}
