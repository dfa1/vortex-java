package io.github.dfa1.vortex.performance;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
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
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VariableWidthFieldVector;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// FSST benchmark: Java writer/reader vs JNI (Rust) writer/reader on a single
/// high-cardinality Utf8 column that the cost-based dispatch routes through FSST.
///
/// This is the PRE-REWRITE baseline for issue #287's FSST rewrite: it exercises the
/// existing, unmodified `vortex.fsst` writer/reader adapters (which don't change until
/// PR 5 of that sequence), so re-running the identical benchmark after the rewrite lands
/// shows the "arc" from slow to fast with zero benchmark-code changes.
///
/// Corpus: synthetic log-line-like strings (timestamp + level + HTTP method + URL path +
/// status). The fixed vocabulary (levels, methods, path segments, statuses) gives FSST's
/// symbol table real repeated substrings to learn, while the embedded per-row counters and
/// ids keep cardinality high enough that Dict loses the cost competition to FSST — unlike
/// purely-random bytes, which would just hit FSST's escape floor and not exercise the
/// algorithm meaningfully.
///
/// Row count: 1 M across 20 chunks of 50 k. FSST trains per column/per chunk, so this is
/// enough to dominate per-call fixed overhead while staying fast to iterate on; @Setup
/// verifies via the inspector that `vortex.fsst` was actually the selected encoding, so a
/// future dispatch change can't silently turn this into a no-op benchmark of some other
/// encoding.
///
/// Run: java -jar performance/target/benchmarks.jar JavaVsJniFsstBenchmark
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
public class JavaVsJniFsstBenchmark {

    private static final int TOTAL_ROWS = 1_000_000;
    private static final int BATCH_SIZE = 50_000;
    private static final int NUM_BATCHES = TOTAL_ROWS / BATCH_SIZE;

    private static final ColumnName LINE = ColumnName.of("line");
    private static final DType.Struct JAVA_SCHEMA = new DType.Struct(
            List.of(LINE), List.of(DType.UTF8), false);
    private static final Schema JNI_SCHEMA = new Schema(List.of(
            Field.notNullable("line", ArrowType.Utf8.INSTANCE)));

    private static final String[] LEVELS = {"INFO", "WARN", "ERROR", "DEBUG", "TRACE"};
    private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH"};
    private static final String[] SEGMENTS = {
            "api", "v1", "v2", "users", "orders", "products", "sessions", "auth",
            "search", "checkout", "inventory", "billing", "reports", "settings"
    };
    private static final String[] STATUSES = {
            "200 OK", "201 Created", "204 No Content", "301 Moved Permanently",
            "400 Bad Request", "401 Unauthorized", "404 Not Found", "500 Internal Server Error"
    };

    private static final Session SESSION = Session.create();

    static {
        NativeLoader.loadJni();
    }

    // Pre-generated per-batch corpus — filled once in @Setup, reused across invocations.
    private String[][] batchLines;
    private byte[][][] batchLineBytes;

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

        javaWriteFile = Files.createTempFile("fsst-java-write", ".vtx");
        jniWriteFile = Files.createTempFile("fsst-jni-write", ".vtx");
        javaReadFile = Files.createTempFile("fsst-java-read", ".vtx");
        jniReadFile = Files.createTempFile("fsst-jni-read", ".vtx");

        batchLines = new String[NUM_BATCHES][BATCH_SIZE];
        batchLineBytes = new byte[NUM_BATCHES][BATCH_SIZE][];
        var rng = new Random(42L);
        for (int b = 0; b < NUM_BATCHES; b++) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                String line = logLine(rng, b * BATCH_SIZE + i);
                batchLines[b][i] = line;
                batchLineBytes[b][i] = line.getBytes(StandardCharsets.UTF_8);
            }
        }

        // Pre-write the read-benchmark inputs once (not measured), one per implementation.
        writeJava(javaReadFile);
        writeJni(jniReadFile);

        verifyFsstSelected(javaReadFile);

        System.out.printf("[JavaVsJniFsstBenchmark] corpus pre-generated: %d rows in %d batches; "
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

    /// Java write: encode and write 1 M log lines via Java VortexWriter (FSST column).
    @Benchmark
    public long javaFsstEncode() throws IOException {
        writeJava(javaWriteFile);
        return Files.size(javaWriteFile);
    }

    /// JNI write: encode and write 1 M log lines via Rust VortexWriter.
    @Benchmark
    public long jniFsstEncode() throws IOException {
        writeJni(jniWriteFile);
        return Files.size(jniWriteFile);
    }

    /// Java read: scan the FSST column, sum decoded byte lengths.
    @Benchmark
    public long javaFsstDecode() throws IOException {
        long[] sum = {0L};
        try (VortexReader vf = VortexReader.open(javaReadFile, registry);
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("line"))) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    VarBinArray line = c.column("line");
                    line.forEachByteLength(v -> sum[0] += v);
                }
            }
        }
        return sum[0];
    }

    /// JNI read: scan the FSST column, sum decoded byte lengths.
    @Benchmark
    public long jniFsstDecode() throws IOException {
        String uri = jniReadFile.toAbsolutePath().toUri().toString();
        var opts = dev.vortex.api.ScanOptions.builder()
                           .projection(Expression.select(new String[]{"line"}, Expression.root()))
                           .build();

        long sum = 0L;
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(allocator)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    // The Rust FSST reader returns an Arrow StringView (ViewVarCharVector), not a
                    // plain VarCharVector — read through the interface both implement.
                    VariableWidthFieldVector lineVec = (VariableWidthFieldVector) root.getVector("line");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        sum += lineVec.get(i).length;
                    }
                }
            }
        }
        return sum;
    }

    // ── Corpus + writers ────────────────────────────────────────────────────────

    private static String logLine(Random rng, int row) {
        String level = LEVELS[rng.nextInt(LEVELS.length)];
        String method = METHODS[rng.nextInt(METHODS.length)];
        String status = STATUSES[rng.nextInt(STATUSES.length)];
        String path = "/" + SEGMENTS[rng.nextInt(SEGMENTS.length)]
                + "/" + SEGMENTS[rng.nextInt(SEGMENTS.length)]
                + "/" + (1000 + rng.nextInt(9_000_000));
        // Embedded epoch millis + request id keep cardinality high so FSST — not Dict — wins,
        // while the fixed vocabulary above gives the symbol table real repeated substrings.
        long millis = 1_700_000_000_000L + (long) row * 37L;
        int requestId = row ^ (row << 7);
        return millis + " [" + level + "] " + method + " " + path
                + " -> " + status + " req=" + Integer.toHexString(requestId);
    }

    private void writeJava(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(
                     ch, JAVA_SCHEMA, WriteOptions.cascading(3).withGlobalDict(false))) {
            for (int b = 0; b < NUM_BATCHES; b++) {
                writer.writeChunk(Map.of(LINE, batchLines[b]));
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
            VarCharVector lineVec = (VarCharVector) root.getVector("line");
            int n = batchLineBytes[b].length;
            lineVec.allocateNew();
            for (int i = 0; i < n; i++) {
                lineVec.setSafe(i, batchLineBytes[b][i]);
            }
            root.setRowCount(n);

            try (ArrowArray arr = ArrowArray.allocateNew(allocator);
                 ArrowSchema schema = ArrowSchema.allocateNew(allocator)) {
                Data.exportVectorSchemaRoot(allocator, root, null, arr, schema);
                writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
            }
        }
    }

    /// Fails loudly if the Java writer did not route the column through `vortex.fsst`, so a
    /// future dispatch change can't silently turn the decode benchmarks into a no-op measurement
    /// of some other encoding.
    private void verifyFsstSelected(Path path) throws IOException {
        try (VortexReader vf = VortexReader.open(path, registry)) {
            InspectorTree tree = InspectorTree.build(vf);
            if (!tree.usedEncodings().contains("vortex.fsst")) {
                throw new VortexException("expected vortex.fsst to be selected, but used encodings were "
                        + tree.usedEncodings());
            }
        }
    }
}
