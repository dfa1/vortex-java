package io.github.dfa1.vortex.performance;

import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.DateUnit;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Write benchmark: Java writer vs JNI (Rust) writer on the same OHLC dataset.
///
/// Schema: date(I32/DATE32), symbol(Utf8), open/high/low/close(F64), volume(I64) — 7 columns.
///
/// Data is pre-generated in @Setup so only encoding + I/O is measured.
/// Each invocation writes 10 M rows; the file size is returned as the benchmark result
/// so the JVM cannot eliminate the write as dead code.
///
/// Run: java -jar performance/target/benchmarks.jar JavaVsJniWriteBenchmark
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
public class JavaVsJniWriteBenchmark {

    private static final int TOTAL_ROWS = 10_000_000;
    private static final int BATCH_SIZE = 50_000;
    private static final int NUM_BATCHES = TOTAL_ROWS / BATCH_SIZE;

    private static final ArrowType F64_TYPE = new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    private static final Schema JNI_SCHEMA = new Schema(List.of(
            Field.notNullable("date", new ArrowType.Date(DateUnit.DAY)),
            Field.notNullable("symbol", ArrowType.Utf8.INSTANCE),
            Field.notNullable("open", F64_TYPE),
            Field.notNullable("high", F64_TYPE),
            Field.notNullable("low", F64_TYPE),
            Field.notNullable("close", F64_TYPE),
            Field.notNullable("volume", new ArrowType.Int(64, true))
    ));

    private static final DType.Struct JAVA_SCHEMA = new DType.Struct(
            List.of("date", "symbol", "open", "high", "low", "close", "volume"),
            List.of(
                    new DType.Primitive(PType.I32, false),
                    new DType.Utf8(false),
                    new DType.Primitive(PType.F64, false),
                    new DType.Primitive(PType.F64, false),
                    new DType.Primitive(PType.F64, false),
                    new DType.Primitive(PType.F64, false),
                    new DType.Primitive(PType.I64, false)
            ),
            false
    );

    // Real Nasdaq tickers — mix of lengths (1–5 chars) for realistic varbin distribution.
    private static final String[] NASDAQ_TICKERS = {
            "AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "TSLA", "AVGO", "COST", "NFLX",
            "AMD", "ADBE", "QCOM", "PEP", "CSCO", "TXN", "INTC", "CMCSA", "INTU", "AMGN",
            "HON", "AMAT", "MU", "LRCX", "KLAC", "MRVL", "PANW", "SNPS", "CDNS", "REGN"
    };
    private static final byte[][] TICKER_BYTES;
    private static final Session SESSION = Session.create();

    static {
        TICKER_BYTES = new byte[NASDAQ_TICKERS.length][];
        for (int i = 0; i < NASDAQ_TICKERS.length; i++) {
            TICKER_BYTES[i] = NASDAQ_TICKERS[i].getBytes(StandardCharsets.UTF_8);
        }
    }

    static {
        NativeLoader.loadJni();
    }

    // Pre-generated batch data — filled once in @Setup, reused across invocations.
    private int[][] batchDates;
    private String[][] batchSymbols;
    private double[][] batchOpen;
    private double[][] batchHigh;
    private double[][] batchLow;
    private double[][] batchClose;
    private long[][] batchVolume;

    private Path jniFile;
    private Path javaFile;
    private Path javaFileCascading;
    private BufferAllocator allocator;

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @Setup(Level.Trial)
    public void setup() throws IOException {
        allocator = ArrowAllocation.rootAllocator();
        jniFile = Files.createTempFile("ohlc-jni-write", ".vtx");
        javaFile = Files.createTempFile("ohlc-java-write", ".vtx");
        javaFileCascading = Files.createTempFile("ohlc-java-cascading-write", ".vtx");

        batchDates = new int[NUM_BATCHES][BATCH_SIZE];
        batchSymbols = new String[NUM_BATCHES][BATCH_SIZE];
        batchOpen = new double[NUM_BATCHES][BATCH_SIZE];
        batchHigh = new double[NUM_BATCHES][BATCH_SIZE];
        batchLow = new double[NUM_BATCHES][BATCH_SIZE];
        batchClose = new double[NUM_BATCHES][BATCH_SIZE];
        batchVolume = new long[NUM_BATCHES][BATCH_SIZE];

        double[] prices = new double[NASDAQ_TICKERS.length];
        Arrays.fill(prices, 100.0);
        var rng = new Random(42L);
        int day = (int) LocalDate.of(2020, 1, 2).toEpochDay();

        for (int b = 0; b < NUM_BATCHES; b++) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                int ticker = i % NASDAQ_TICKERS.length;
                double px = prices[ticker];
                double ret = rng.nextGaussian() * 0.02;
                double o = round(px * (1 + ret * 0.3));
                double c = round(px * (1 + ret));
                double spread = Math.abs(px * rng.nextDouble() * 0.03);
                double h = round(Math.max(o, c) + spread);
                double l = round(Math.min(o, c) - spread);
                batchDates[b][i] = day + (b * BATCH_SIZE + i) / NASDAQ_TICKERS.length;
                batchSymbols[b][i] = NASDAQ_TICKERS[ticker];
                batchOpen[b][i] = o;
                batchHigh[b][i] = h;
                batchLow[b][i] = l;
                batchClose[b][i] = c;
                batchVolume[b][i] = Math.max(100_000L, Math.round(1_000_000 + rng.nextGaussian() * 200_000));
                prices[ticker] = c;
            }
            day += BATCH_SIZE / NASDAQ_TICKERS.length;
        }

        System.out.printf("[JavaVsJniWriteBenchmark] data pre-generated: %d rows in %d batches%n",
                TOTAL_ROWS, NUM_BATCHES);
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException {
        if (Files.exists(javaFile) && Files.exists(javaFileCascading)) {
            long plain = Files.size(javaFile);
            long cascading = Files.size(javaFileCascading);
            System.out.printf("[JavaVsJniWriteBenchmark] cascading/plain size ratio: %.3f (%d B / %d B)%n",
                    (double) cascading / plain, cascading, plain);
        }
        Files.deleteIfExists(jniFile);
        Files.deleteIfExists(javaFile);
        Files.deleteIfExists(javaFileCascading);
    }

    /// JNI write: encode and write 10 M rows via Rust VortexWriter.
    @Benchmark
    public long jniWrite() throws IOException {
        try (dev.vortex.api.VortexWriter writer = dev.vortex.api.VortexWriter.create(
                SESSION, jniFile.toAbsolutePath().toUri().toString(), JNI_SCHEMA, new HashMap<>(), allocator)) {
            for (int b = 0; b < NUM_BATCHES; b++) {
                flushJni(writer, b);
            }
        }
        return Files.size(jniFile);
    }

    /// Java write with cascading compression (depth 3): ALP → FOR → bitpacked.
    @Benchmark
    public long javaWriteCascading() throws IOException {
        try (FileChannel ch = FileChannel.open(javaFileCascading,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(ch, JAVA_SCHEMA, WriteOptions.cascading(3))) {
            for (int b = 0; b < NUM_BATCHES; b++) {
                Map<String, Object> chunk = Map.of(
                        "date", batchDates[b],
                        "symbol", batchSymbols[b],
                        "open", batchOpen[b],
                        "high", batchHigh[b],
                        "low", batchLow[b],
                        "close", batchClose[b],
                        "volume", batchVolume[b]
                );
                writer.writeChunk(chunk);
            }
        }
        return Files.size(javaFileCascading);
    }

    /// Java write: encode and write 10 M rows via Java VortexWriter.
    @Benchmark
    public long javaWrite() throws IOException {
        try (FileChannel ch = FileChannel.open(javaFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(ch, JAVA_SCHEMA, WriteOptions.defaults())) {
            for (int b = 0; b < NUM_BATCHES; b++) {
                Map<String, Object> chunk = Map.of(
                        "date", batchDates[b],
                        "symbol", batchSymbols[b],
                        "open", batchOpen[b],
                        "high", batchHigh[b],
                        "low", batchLow[b],
                        "close", batchClose[b],
                        "volume", batchVolume[b]
                );
                writer.writeChunk(chunk);
            }
        }
        return Files.size(javaFile);
    }

    private void flushJni(dev.vortex.api.VortexWriter writer, int b) throws IOException {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(JNI_SCHEMA, allocator)) {
            DateDayVector dateVec = (DateDayVector) root.getVector("date");
            VarCharVector symbolVec = (VarCharVector) root.getVector("symbol");
            Float8Vector openVec = (Float8Vector) root.getVector("open");
            Float8Vector highVec = (Float8Vector) root.getVector("high");
            Float8Vector lowVec = (Float8Vector) root.getVector("low");
            Float8Vector closeVec = (Float8Vector) root.getVector("close");
            BigIntVector volVec = (BigIntVector) root.getVector("volume");

            int n = batchDates[b].length;
            dateVec.allocateNew(n);
            symbolVec.allocateNew(n);
            openVec.allocateNew(n);
            highVec.allocateNew(n);
            lowVec.allocateNew(n);
            closeVec.allocateNew(n);
            volVec.allocateNew(n);

            for (int i = 0; i < n; i++) {
                dateVec.setSafe(i, batchDates[b][i]);
                symbolVec.setSafe(i, TICKER_BYTES[i % NASDAQ_TICKERS.length]);
                openVec.setSafe(i, batchOpen[b][i]);
                highVec.setSafe(i, batchHigh[b][i]);
                lowVec.setSafe(i, batchLow[b][i]);
                closeVec.setSafe(i, batchClose[b][i]);
                volVec.setSafe(i, batchVolume[b][i]);
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
