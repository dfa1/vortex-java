package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.encoding.CodecRegistry;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies that Java and JNI writers produce valid, readable files for the same numeric OHLC dataset,
/// and captures file sizes for regression visibility.
///
/// Schema: date(I32), open/high/low/close(F64), volume(I64) — 6 columns, no strings.
class WriteFileSizeIntegrationTest {

	private static final int TOTAL_ROWS = 100_000;
	private static final int BATCH_SIZE = 50_000;

	private static final ArrowType F64_TYPE = new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
	private static final Schema JNI_SCHEMA = new Schema(List.of(
			Field.notNullable("date", new ArrowType.Date(DateUnit.DAY)),
			Field.notNullable("open", F64_TYPE),
			Field.notNullable("high", F64_TYPE),
			Field.notNullable("low", F64_TYPE),
			Field.notNullable("close", F64_TYPE),
			Field.notNullable("volume", new ArrowType.Int(64, true))
	));

	private static final DType.Struct JAVA_SCHEMA = new DType.Struct(
			List.of("date", "open", "high", "low", "close", "volume"),
			List.of(
					new DType.Primitive(PType.I32, false),
					new DType.Primitive(PType.F64, false),
					new DType.Primitive(PType.F64, false),
					new DType.Primitive(PType.F64, false),
					new DType.Primitive(PType.F64, false),
					new DType.Primitive(PType.I64, false)
			),
			false
	);

	private static final Session SESSION = Session.create();
	private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();

	static {
		NativeLoader.loadJni();
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	// ── Data generation ───────────────────────────────────────────────────────

	private record OhlcBatch(int[] dates, double[] open, double[] high, double[] low,
	                         double[] close, long[] volume) {
	}

	private static List<OhlcBatch> generateBatches() {
		double[] prices = new double[30];
		Arrays.fill(prices, 100.0);
		var rng = new Random(42L);
		int startDay = (int) LocalDate.of(2020, 1, 2).toEpochDay();
		int rowsLeft = TOTAL_ROWS;
		int rowsDone = 0;
		var batches = new java.util.ArrayList<OhlcBatch>();

		while (rowsLeft > 0) {
			int n = Math.min(rowsLeft, BATCH_SIZE);
			int[] dates = new int[n];
			double[] open = new double[n], high = new double[n], low = new double[n], close = new double[n];
			long[] volume = new long[n];
			for (int i = 0; i < n; i++) {
				int ticker = i % 30;
				double px = prices[ticker];
				double ret = rng.nextGaussian() * 0.02;
				double o = round(px * (1 + ret * 0.3));
				double c = round(px * (1 + ret));
				double spread = Math.abs(px * rng.nextDouble() * 0.03);
				dates[i] = startDay + (rowsDone + i) / 30;
				open[i]  = o;
				high[i]  = round(Math.max(o, c) + spread);
				low[i]   = round(Math.min(o, c) - spread);
				close[i] = c;
				volume[i] = Math.max(100_000L, Math.round(1_000_000 + rng.nextGaussian() * 200_000));
				prices[ticker] = c;
			}
			batches.add(new OhlcBatch(dates, open, high, low, close, volume));
			rowsLeft -= n;
			rowsDone += n;
		}
		return batches;
	}

	// ── Writers ───────────────────────────────────────────────────────────────

	private static void writeJava(Path file, List<OhlcBatch> batches) throws IOException {
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     VortexWriter writer = VortexWriter.create(ch, JAVA_SCHEMA, WriteOptions.defaults())) {
			for (OhlcBatch b : batches) {
				writer.writeChunk(Map.of(
						"date", b.dates(), "open", b.open(), "high", b.high(),
						"low", b.low(), "close", b.close(), "volume", b.volume()
				));
			}
		}
	}

	private static void writeJni(Path file, List<OhlcBatch> batches) throws IOException {
		String uri = file.toAbsolutePath().toUri().toString();
		try (dev.vortex.api.VortexWriter writer = dev.vortex.api.VortexWriter.create(
				SESSION, uri, JNI_SCHEMA, new HashMap<>(), ALLOCATOR)) {
			for (OhlcBatch b : batches) {
				try (VectorSchemaRoot root = VectorSchemaRoot.create(JNI_SCHEMA, ALLOCATOR)) {
					DateDayVector dateVec  = (DateDayVector) root.getVector("date");
					Float8Vector  openVec  = (Float8Vector)  root.getVector("open");
					Float8Vector  highVec  = (Float8Vector)  root.getVector("high");
					Float8Vector  lowVec   = (Float8Vector)  root.getVector("low");
					Float8Vector  closeVec = (Float8Vector)  root.getVector("close");
					BigIntVector  volVec   = (BigIntVector)  root.getVector("volume");

					int n = b.dates().length;
					dateVec.allocateNew(n);
					openVec.allocateNew(n);
					highVec.allocateNew(n);
					lowVec.allocateNew(n);
					closeVec.allocateNew(n);
					volVec.allocateNew(n);

					for (int i = 0; i < n; i++) {
						dateVec.setSafe(i, b.dates()[i]);
						openVec.setSafe(i, b.open()[i]);
						highVec.setSafe(i, b.high()[i]);
						lowVec.setSafe(i, b.low()[i]);
						closeVec.setSafe(i, b.close()[i]);
						volVec.setSafe(i, b.volume()[i]);
					}
					root.setRowCount(n);

					try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
					     ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
						Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, schema);
						writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
					}
				}
			}
		}
	}

	// ── Tests ─────────────────────────────────────────────────────────────────

	@Test
	void javaFile_isReadable_withCorrectRowCount(@TempDir Path tmp) throws IOException {
		// Given
		Path file = tmp.resolve("ohlc-java.vtx");
		List<OhlcBatch> batches = generateBatches();

		// When
		writeJava(file, batches);

		// Then — readable and correct row count
		long totalRows = 0L;
		try (VortexReader reader = VortexReader.open(file, CodecRegistry.loadAll())) {
			var iter = reader.scan(io.github.dfa1.vortex.scan.ScanOptions.columns("volume"));
			while (iter.hasNext()) {
				totalRows += iter.next().<LongArray>column("volume").length();
			}
		}
		assertThat(totalRows).isEqualTo(TOTAL_ROWS);
	}

	@Test
	void jniFile_isReadable_withCorrectRowCount(@TempDir Path tmp) throws IOException {
		// Given
		Path file = tmp.resolve("ohlc-jni.vtx");
		List<OhlcBatch> batches = generateBatches();

		// When
		writeJni(file, batches);

		// Then — readable and correct row count
		long totalRows = 0L;
		try (VortexReader reader = VortexReader.open(file, CodecRegistry.loadAll())) {
			var iter = reader.scan(io.github.dfa1.vortex.scan.ScanOptions.columns("close"));
			while (iter.hasNext()) {
				totalRows += iter.next().<DoubleArray>column("close").length();
			}
		}
		assertThat(totalRows).isEqualTo(TOTAL_ROWS);
	}

	@Test
	void fileSizeComparison(@TempDir Path tmp) throws IOException {
		// Given
		Path javaFile = tmp.resolve("ohlc-java.vtx");
		Path jniFile  = tmp.resolve("ohlc-jni.vtx");
		List<OhlcBatch> batches = generateBatches();

		// When
		writeJava(javaFile, batches);
		writeJni(jniFile, batches);

		long javaSize = Files.size(javaFile);
		long jniSize  = Files.size(jniFile);

		// Then
		System.out.printf("[WriteFileSizeIntegrationTest] %d rows: Java=%,d bytes (%.1f MB)  JNI=%,d bytes (%.1f MB)  ratio=%.2fx%n",
				TOTAL_ROWS,
				javaSize, javaSize / 1_048_576.0,
				jniSize,  jniSize  / 1_048_576.0,
				(double) javaSize / jniSize);

		assertThat(javaSize).isPositive();
		assertThat(jniSize).isPositive();
		// Java writer uses only PrimitiveCodec (no ALP/bitpacking), so larger files are expected.
		// Bound: Java file < 6× JNI to catch gross regressions (e.g. duplicate data, unbounded growth).
		assertThat(javaSize).isLessThan(jniSize * 6);
	}
}
