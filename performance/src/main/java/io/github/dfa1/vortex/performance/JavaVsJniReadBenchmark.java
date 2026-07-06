package io.github.dfa1.vortex.performance;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.Chunk;
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
import org.apache.arrow.vector.ipc.ArrowReader;
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

/// Read benchmark: JNI Vortex reader vs Java reader on the same file.
///
/// The file is written by the JNI VortexWriter in `@Setup`, giving a realistic
/// file with whatever encodings the Rust implementation chooses.
///
/// Both benchmarks project onto the "close" column (F64) and sum all values so
/// the JVM can't optimize away the decode work.
///
/// Run: java -jar performance/target/benchmarks.jar JavaVsJniReadBenchmark
///
/// To test against a pre-existing JNI-written file:
///   java -Dvortex.bench.ohlc=/path/to/file.vtx -jar target/benchmarks.jar JavaVsJniReadBenchmark
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
public class JavaVsJniReadBenchmark {

    private static final int TOTAL_ROWS = Integer.getInteger("vortex.bench.ohlc.rows", 10_000_000);
    private static final int BATCH_SIZE = 50_000;
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
    private static final Session SESSION = Session.create();
    private static final DType.Struct JAVA_SCHEMA = new DType.Struct(
            List.of(ColumnName.of("date"), ColumnName.of("symbol"), ColumnName.of("open"), ColumnName.of("high"), ColumnName.of("low"), ColumnName.of("close"), ColumnName.of("volume")),
            List.of(
                    DType.I32,
                    DType.UTF8,
                    DType.F64,
                    DType.F64,
                    DType.F64,
                    DType.F64,
                    DType.I64
            ),
            false);
    private static final Object FILE_LOCK = new Object();
    // Real Nasdaq tickers — mix of lengths (1–5 chars) for realistic varbin distribution.
    private static final String[] NASDAQ_TICKERS = {
            "AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "TSLA", "AVGO", "COST", "NFLX",
            "AMD", "ADBE", "QCOM", "PEP", "CSCO", "TXN", "INTC", "CMCSA", "INTU", "AMGN",
            "HON", "AMAT", "MU", "LRCX", "KLAC", "MRVL", "PANW", "SNPS", "CDNS", "REGN"
    };
    private static final byte[][] TICKER_BYTES;
    private static Path sharedBenchFile;
    private static boolean sharedOwnFile;
    private static Path sharedCascadingFile;
    private static int sharedRefCount;

    static {
        NativeLoader.loadJni();
    }

    static {
        TICKER_BYTES = new byte[NASDAQ_TICKERS.length][];
        for (int i = 0; i < NASDAQ_TICKERS.length; i++) {
            TICKER_BYTES[i] = NASDAQ_TICKERS[i].getBytes(StandardCharsets.UTF_8);
        }
    }

    private Path benchFile;
    private Path cascadingFile;
    ReadRegistry registry;
    private BufferAllocator allocator;

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @Setup(Level.Trial)
    public void setup() throws IOException {
        registry = ReadRegistry.loadAll();
        allocator = ArrowAllocation.rootAllocator();

        synchronized (FILE_LOCK) {
            if (sharedBenchFile == null) {
                String externalFile = System.getProperty("vortex.bench.ohlc");
                if (externalFile != null && !externalFile.isEmpty()) {
                    sharedBenchFile = Path.of(externalFile);
                    sharedOwnFile = false;
                    if (!Files.exists(sharedBenchFile) || Files.size(sharedBenchFile) == 0L) {
                        System.out.printf("[JavaVsJniReadBenchmark] external file missing — writing %d OHLC rows via JNI to %s...%n",
                                TOTAL_ROWS, sharedBenchFile);
                        writeJni(sharedBenchFile);
                        System.out.printf("[JavaVsJniReadBenchmark] file size: %.1f MB%n",
                                Files.size(sharedBenchFile) / 1_048_576.0);
                    } else {
                        System.out.printf("[JavaVsJniReadBenchmark] using external file: %s%n", sharedBenchFile);
                    }
                } else {
                    sharedBenchFile = Files.createTempFile("ohlc-bench", ".vtx");
                    sharedOwnFile = true;
                    System.out.printf("[JavaVsJniReadBenchmark] writing %d OHLC rows via JNI...%n", TOTAL_ROWS);
                    writeJni(sharedBenchFile);
                    System.out.printf("[JavaVsJniReadBenchmark] file size: %.1f MB%n",
                            Files.size(sharedBenchFile) / 1_048_576.0);
                }
            }
            if (sharedCascadingFile == null) {
                sharedCascadingFile = Files.createTempFile("ohlc-java-cascading", ".vtx");
                System.out.printf("[JavaVsJniReadBenchmark] writing %d OHLC rows (cascading depth 3) via Java...%n", TOTAL_ROWS);
                writeJavaCascading(sharedCascadingFile);
                System.out.printf("[JavaVsJniReadBenchmark] cascading file size: %.1f MB%n",
                        Files.size(sharedCascadingFile) / 1_048_576.0);
            }
            sharedRefCount++;
            benchFile = sharedBenchFile;
            cascadingFile = sharedCascadingFile;
        }
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException {
        synchronized (FILE_LOCK) {
            sharedRefCount--;
            if (sharedRefCount == 0) {
                if (sharedOwnFile) {
                    Files.deleteIfExists(sharedBenchFile);
                    sharedBenchFile = null;
                }
                if (sharedCascadingFile != null) {
                    Files.deleteIfExists(sharedCascadingFile);
                    sharedCascadingFile = null;
                }
            }
        }
    }

    // ── Cascading (Java-written) benchmarks ──────────────────────────────────

    /// JNI read: project on "volume", sum all values.
    @Benchmark
    public long jniReadVolume() throws IOException {
        String uri = benchFile.toAbsolutePath().toUri().toString();
        var opts = ScanOptions.builder()
                           .projection(Expression.select(new String[]{"volume"}, Expression.root()))
                           .build();

        long sum = 0L;
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(allocator)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    BigIntVector volumeVec = (BigIntVector) root.getVector("volume");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        sum += volumeVec.get(i);
                    }
                }
            }
        }
        return sum;
    }

    // ── JNI file generation ───────────────────────────────────────────────────

    /// JNI read: project on "close" (F64, ALP-encoded), sum all values.
    @Benchmark
    public double jniReadClose() throws IOException {
        String uri = benchFile.toAbsolutePath().toUri().toString();
        var opts = ScanOptions.builder()
                           .projection(Expression.select(new String[]{"close"}, Expression.root()))
                           .build();

        double sum = 0.0;
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(allocator)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    Float8Vector closeVec = (Float8Vector) root.getVector("close");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        sum += closeVec.get(i);
                    }
                }
            }
        }
        return sum;
    }

    /// JNI read: project on "symbol" (short UTF-8 string, varbin), sum byte lengths.
    @Benchmark
    public long jniReadSymbol() throws IOException {
        String uri = benchFile.toAbsolutePath().toUri().toString();
        var opts = ScanOptions.builder()
                           .projection(Expression.select(new String[]{"symbol"}, Expression.root()))
                           .build();

        long sum = 0L;
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(allocator)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    VarCharVector symbolVec = (VarCharVector) root.getVector("symbol");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        sum += symbolVec.getObject(i).getBytes().length;
                    }
                }
            }
        }
        return sum;
    }

    /// Java read: cascading file (depth 3), project on "volume", sum via fold.
    @Benchmark
    public long javaReadCascading() throws IOException {
        long sum = 0L;
        try (VortexReader vf = VortexReader.open(cascadingFile, registry);
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("volume"))) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    LongArray volume = c.column("volume");
                    sum += volume.fold(0L, Long::sum);
                }
            }
        }
        return sum;
    }

    /// Java read: project on "volume", sum all values via fold.
    @Benchmark
    public long javaReadVolume() throws IOException {
        long sum = 0L;
        try (VortexReader vf = VortexReader.open(benchFile, registry);
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("volume"))) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    LongArray volume = c.column("volume");
                    sum += volume.fold(0L, Long::sum);
                }
            }
        }
        return sum;
    }

    /// Java read: project on "close" (F64, ALP-encoded), sum via fold.
    @Benchmark
    public double javaReadClose() throws IOException {
        double sum = 0.0;
        try (VortexReader vf = VortexReader.open(benchFile, registry);
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("close"))) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    DoubleArray close = c.column("close");
                    sum += close.fold(0.0, Double::sum);
                }
            }
        }
        return sum;
    }

    /// Java read: project on "symbol" (short UTF-8 string, varbin), sum byte lengths.
    @Benchmark
    public long javaReadSymbol() throws IOException {
        long[] sum = {0L};
        try (VortexReader vf = VortexReader.open(benchFile, registry);
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.columns("symbol"))) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    VarBinArray symbol = c.column("symbol");
                    symbol.forEachByteLength(v -> sum[0] += v);
                }
            }
        }
        return sum[0];
    }

    // ── Top-N reads: amortize open + footer/layout decode over N rows ────────

    /// Java read: project on "volume", consume only the first 10 rows.
    @Benchmark
    public long javaReadVolumeFirst10() throws IOException {
        return javaReadVolumeFirst(10);
    }

    /// Java read: project on "volume", consume only the first 100 rows.
    @Benchmark
    public long javaReadVolumeFirst100() throws IOException {
        return javaReadVolumeFirst(100);
    }

    /// JNI read: project on "volume", stop after the first 10 rows.
    @Benchmark
    public long jniReadVolumeFirst10() throws IOException {
        return jniReadVolumeFirst(10);
    }

    /// JNI read: project on "volume", stop after the first 100 rows.
    @Benchmark
    public long jniReadVolumeFirst100() throws IOException {
        return jniReadVolumeFirst(100);
    }

    private long javaReadVolumeFirst(long limit) throws IOException {
        long sum = 0L;
        long taken = 0L;
        try (VortexReader vf = VortexReader.open(benchFile, registry);
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.all()
                             .withColumns("volume").withLimit(limit))) {
            while (iter.hasNext() && taken < limit) {
                try (Chunk c = iter.next()) {
                    LongArray volume = c.column("volume");
                    long take = Math.min(volume.length(), limit - taken);
                    for (long i = 0; i < take; i++) {
                        sum += volume.getLong(i);
                    }
                    taken += take;
                }
            }
        }
        return sum;
    }

    private long jniReadVolumeFirst(long limit) throws IOException {
        String uri = benchFile.toAbsolutePath().toUri().toString();
        var opts = ScanOptions.builder()
                           .projection(Expression.select(new String[]{"volume"}, Expression.root()))
                           .build();

        long sum = 0L;
        long taken = 0L;
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        outer:
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(allocator)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    BigIntVector volumeVec = (BigIntVector) root.getVector("volume");
                    int take = (int) Math.min(root.getRowCount(), limit - taken);
                    for (int i = 0; i < take; i++) {
                        sum += volumeVec.get(i);
                    }
                    taken += take;
                    if (taken >= limit) {
                        break outer;
                    }
                }
            }
        }
        return sum;
    }

    private void writeJavaCascading(Path path) throws IOException {
        int[] epochDays = new int[BATCH_SIZE];
        String[] symbols = new String[BATCH_SIZE];
        double[] open = new double[BATCH_SIZE];
        double[] high = new double[BATCH_SIZE];
        double[] low = new double[BATCH_SIZE];
        double[] close = new double[BATCH_SIZE];
        long[] volume = new long[BATCH_SIZE];

        double[] prices = new double[NASDAQ_TICKERS.length];
        Arrays.fill(prices, 100.0);
        var rng = new Random(42L);
        int day = (int) LocalDate.of(2020, 1, 2).toEpochDay();
        int rowsLeft = TOTAL_ROWS;

        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(ch, JAVA_SCHEMA, WriteOptions.cascading(3))) {
            while (rowsLeft > 0) {
                int n = Math.min(rowsLeft, BATCH_SIZE);
                for (int i = 0; i < n; i++) {
                    int ticker = i % NASDAQ_TICKERS.length;
                    double px = prices[ticker];
                    double ret = rng.nextGaussian() * 0.02;
                    double o = round(px * (1 + ret * 0.3));
                    double c = round(px * (1 + ret));
                    double spread = Math.abs(px * rng.nextDouble() * 0.03);
                    double h = round(Math.max(o, c) + spread);
                    double l = round(Math.min(o, c) - spread);
                    epochDays[i] = day + i / NASDAQ_TICKERS.length;
                    symbols[i] = NASDAQ_TICKERS[ticker];
                    open[i] = o;
                    high[i] = h;
                    low[i] = l;
                    close[i] = c;
                    volume[i] = Math.max(100_000L, Math.round(1_000_000 + rng.nextGaussian() * 200_000));
                    prices[ticker] = c;
                }
                day += n / NASDAQ_TICKERS.length;
                writer.writeChunk(Map.of(
                        ColumnName.of("date"), epochDays,
                        ColumnName.of("symbol"), symbols,
                        ColumnName.of("open"), open,
                        ColumnName.of("high"), high,
                        ColumnName.of("low"), low,
                        ColumnName.of("close"), close,
                        ColumnName.of("volume"), volume
                ));
                rowsLeft -= n;
            }
        }
    }

    private void writeJni(Path path) throws IOException {
        String uri = path.toAbsolutePath().toUri().toString();
        try (dev.vortex.api.VortexWriter writer = dev.vortex.api.VortexWriter.create(
                SESSION, uri, JNI_SCHEMA, new HashMap<>(), allocator)) {
            // reuse arrays — filled per-batch
            int[] epochDays = new int[BATCH_SIZE];
            byte[][] symbols = new byte[BATCH_SIZE][];
            double[] open = new double[BATCH_SIZE];
            double[] high = new double[BATCH_SIZE];
            double[] low = new double[BATCH_SIZE];
            double[] close = new double[BATCH_SIZE];
            long[] volume = new long[BATCH_SIZE];

            // Per-ticker price state so each symbol has independent price evolution.
            double[] prices = new double[NASDAQ_TICKERS.length];
            Arrays.fill(prices, 100.0);

            var rng = new Random(42L);
            int day = (int) LocalDate.of(2020, 1, 2).toEpochDay();
            int rowsLeft = TOTAL_ROWS;

            while (rowsLeft > 0) {
                int n = Math.min(rowsLeft, BATCH_SIZE);
                for (int i = 0; i < n; i++) {
                    int ticker = i % NASDAQ_TICKERS.length;
                    double px = prices[ticker];
                    double ret = rng.nextGaussian() * 0.02;
                    double o = round(px * (1 + ret * 0.3));
                    double c = round(px * (1 + ret));
                    double rng2 = Math.abs(px * rng.nextDouble() * 0.03);
                    double h = round(Math.max(o, c) + rng2);
                    double l = round(Math.min(o, c) - rng2);
                    epochDays[i] = day + i / NASDAQ_TICKERS.length;
                    symbols[i] = TICKER_BYTES[ticker];
                    open[i] = o;
                    high[i] = h;
                    low[i] = l;
                    close[i] = c;
                    volume[i] = Math.max(100_000L, Math.round(1_000_000 + rng.nextGaussian() * 200_000));
                    prices[ticker] = c;
                }
                day += n / NASDAQ_TICKERS.length;
                flushJni(writer, epochDays, symbols, open, high, low, close, volume, n);
                rowsLeft -= n;
            }
        }
    }

    private void flushJni(
            dev.vortex.api.VortexWriter writer,
            int[] epochDays, byte[][] symbols,
            double[] open, double[] high, double[] low, double[] close, long[] volume,
            int n
    ) throws IOException {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(JNI_SCHEMA, allocator)) {
            DateDayVector dateVec = (DateDayVector) root.getVector("date");
            VarCharVector symbolVec = (VarCharVector) root.getVector("symbol");
            Float8Vector openVec = (Float8Vector) root.getVector("open");
            Float8Vector highVec = (Float8Vector) root.getVector("high");
            Float8Vector lowVec = (Float8Vector) root.getVector("low");
            Float8Vector closeVec = (Float8Vector) root.getVector("close");
            BigIntVector volumeVec = (BigIntVector) root.getVector("volume");

            dateVec.allocateNew(n);
            symbolVec.allocateNew(n);
            openVec.allocateNew(n);
            highVec.allocateNew(n);
            lowVec.allocateNew(n);
            closeVec.allocateNew(n);
            volumeVec.allocateNew(n);

            for (int i = 0; i < n; i++) {
                dateVec.setSafe(i, epochDays[i]);
                symbolVec.setSafe(i, symbols[i]);
                openVec.setSafe(i, open[i]);
                highVec.setSafe(i, high[i]);
                lowVec.setSafe(i, low[i]);
                closeVec.setSafe(i, close[i]);
                volumeVec.setSafe(i, volume[i]);
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
