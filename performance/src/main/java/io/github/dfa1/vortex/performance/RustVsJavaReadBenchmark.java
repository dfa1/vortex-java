package io.github.dfa1.vortex.performance;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.encoding.CodecRegistry;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanResult;
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
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Read benchmark: JNI Vortex reader vs Java reader on the same file.
///
/// The file is written by the JNI VortexWriter in `@Setup`, giving a realistic
/// file with whatever encodings the Rust implementation chooses.
///
/// Both benchmarks project onto the "close" column (F64) and sum all values so
/// the JVM can't optimise away the decode work.
///
/// Run: java -jar performance/target/benchmarks.jar RustVsJavaReadBenchmark
///
/// To test against a pre-existing JNI-written file:
///   java -Dvortex.bench.ohlc=/path/to/file.vtx -jar target/benchmarks.jar RustVsJavaReadBenchmark
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
public class RustVsJavaReadBenchmark {

	private static final int TOTAL_ROWS = 10_000_000;
	private static final int BATCH_SIZE = 50_000;   // 200 chunks
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

	static {
		NativeLoader.loadJni();
	}

	private Path benchFile;
	private boolean ownFile;
	private CodecRegistry registry;
	private BufferAllocator allocator;

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	@Setup(Level.Trial)
	public void setup() throws IOException {
		registry = CodecRegistry.loadAll();
		allocator = ArrowAllocation.rootAllocator();

		String externalFile = System.getProperty("vortex.bench.ohlc");
		if (externalFile != null && !externalFile.isEmpty()) {
			benchFile = Path.of(externalFile);
			ownFile = false;
			System.out.printf("[RustVsJavaReadBenchmark] using external file: %s%n", benchFile);
		} else {
			benchFile = Files.createTempFile("ohlc-bench", ".vtx");
			ownFile = true;
			System.out.printf("[RustVsJavaReadBenchmark] writing %d OHLC rows via JNI...%n", TOTAL_ROWS);
			writeJni(benchFile);
			System.out.printf("[RustVsJavaReadBenchmark] file size: %.1f MB%n",
					Files.size(benchFile) / 1_048_576.0);
		}
	}

	@TearDown(Level.Trial)
	public void cleanup() throws IOException {
		if (ownFile) {
			Files.deleteIfExists(benchFile);
		}
	}

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

	// ── JNI file generation ───────────────────────────────────────────────────

	/// Java read: project on "volume", sum all values via fold.
	@Benchmark
	public long javaReadVolume() throws IOException {
		long sum = 0L;
		try (VortexReader vf = VortexReader.open(benchFile, registry)) {
			var iter = vf.scan(io.github.dfa1.vortex.scan.ScanOptions.columns("volume"));
			while (iter.hasNext()) {
				ScanResult r = iter.next();
				sum += r.<LongArray>column("volume").fold(0L, Long::sum);
			}
		}
		return sum;
	}

	/// Java read: project on "close" (F64, ALP-encoded), sum via fold.
	@Benchmark
	public double javaReadClose() throws IOException {
		double sum = 0.0;
		try (VortexReader vf = VortexReader.open(benchFile, registry)) {
			var iter = vf.scan(io.github.dfa1.vortex.scan.ScanOptions.columns("close"));
			while (iter.hasNext()) {
				ScanResult r = iter.next();
				sum += r.<DoubleArray>column("close").fold(0.0, Double::sum);
			}
		}
		return sum;
	}

	/// Java read: project on "symbol" (short UTF-8 string, varbin), sum byte lengths.
	@Benchmark
	public long javaReadSymbol() throws IOException {
		long[] sum = {0L};
		try (VortexReader vf = VortexReader.open(benchFile, registry)) {
			var iter = vf.scan(io.github.dfa1.vortex.scan.ScanOptions.columns("symbol"));
			while (iter.hasNext()) {
				ScanResult r = iter.next();
				((VarBinArray) r.columns().get("symbol")).forEachByteLength(v -> sum[0] += v);
			}
		}
		return sum[0];
	}

	// Real Nasdaq tickers — mix of lengths (1–5 chars) for realistic varbin distribution.
	private static final String[] NASDAQ_TICKERS = {
			"AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "TSLA", "AVGO", "COST", "NFLX",
			"AMD", "ADBE", "QCOM", "PEP", "CSCO", "TXN", "INTC", "CMCSA", "INTU", "AMGN",
			"HON", "AMAT", "MU", "LRCX", "KLAC", "MRVL", "PANW", "SNPS", "CDNS", "REGN"
	};
	private static final byte[][] TICKER_BYTES;

	static {
		TICKER_BYTES = new byte[NASDAQ_TICKERS.length][];
		for (int i = 0; i < NASDAQ_TICKERS.length; i++) {
			TICKER_BYTES[i] = NASDAQ_TICKERS[i].getBytes(StandardCharsets.UTF_8);
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
