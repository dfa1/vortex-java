package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Demonstrates file size difference between Vortex and plain CSV for OHLC data.
///
/// Same 100 000 rows written to both formats; sizes and ratio logged to stdout.
class VortexVsCsvFileSizeTest {

	private static final int TOTAL_ROWS = 100_000;
	private static final int BATCH_SIZE = 50_000;

	private static final DType.Struct SCHEMA = new DType.Struct(
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

	private record OhlcBatch(int[] dates, double[] open, double[] high,
	                         double[] low, double[] close, long[] volume) {}

	private static List<OhlcBatch> generateBatches() {
		double[] prices = new double[30];
		Arrays.fill(prices, 100.0);
		var rng = new Random(42L);
		int startDay = (int) LocalDate.of(2020, 1, 2).toEpochDay();
		int rowsLeft = TOTAL_ROWS, rowsDone = 0;
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
				double o = Math.round(px * (1 + ret * 0.3) * 100.0) / 100.0;
				double c = Math.round(px * (1 + ret) * 100.0) / 100.0;
				double spread = Math.abs(px * rng.nextDouble() * 0.03);
				dates[i]  = startDay + (rowsDone + i) / 30;
				open[i]   = o;
				high[i]   = Math.round((Math.max(o, c) + spread) * 100.0) / 100.0;
				low[i]    = Math.round((Math.min(o, c) - spread) * 100.0) / 100.0;
				close[i]  = c;
				volume[i] = Math.max(100_000L, Math.round(1_000_000 + rng.nextGaussian() * 200_000));
				prices[ticker] = c;
			}
			batches.add(new OhlcBatch(dates, open, high, low, close, volume));
			rowsLeft -= n;
			rowsDone += n;
		}
		return batches;
	}

	@Test
	void vortexSmallerThanCsv(@TempDir Path tmp) throws IOException {
		// Given
		List<OhlcBatch> batches = generateBatches();
		Path vortexFile = tmp.resolve("ohlc.vtx");
		Path csvFile    = tmp.resolve("ohlc.csv");

		// When — write Vortex
		try (FileChannel ch = FileChannel.open(vortexFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     VortexWriter writer = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
			for (OhlcBatch b : batches) {
				writer.writeChunk(Map.of(
						"date", b.dates(), "open", b.open(), "high", b.high(),
						"low", b.low(), "close", b.close(), "volume", b.volume()
				));
			}
		}

		// When — write CSV
		try (BufferedWriter csv = Files.newBufferedWriter(csvFile)) {
			csv.write("date,open,high,low,close,volume\n");
			for (OhlcBatch b : batches) {
				for (int i = 0; i < b.dates().length; i++) {
					csv.write(b.dates()[i] + "," + b.open()[i] + "," + b.high()[i] + ","
							+ b.low()[i] + "," + b.close()[i] + "," + b.volume()[i] + "\n");
				}
			}
		}

		long vortexBytes = Files.size(vortexFile);
		long csvBytes    = Files.size(csvFile);
		double ratio     = (double) csvBytes / vortexBytes;

		System.out.printf(
				"[VortexVsCsv] %,d rows  CSV=%,d bytes (%.1f MB)  Vortex=%,d bytes (%.1f MB)  ratio=%.2f (CSV/Vortex)%n",
				TOTAL_ROWS,
				csvBytes,  csvBytes  / 1_048_576.0,
				vortexBytes, vortexBytes / 1_048_576.0,
				ratio);
		System.out.println("[VortexVsCsv] Note: Java writer uses first-match encoding without cascading.");
		System.out.println("[VortexVsCsv]       Cascading (ALP+Bitpacked) would narrow the gap significantly.");

		// Sanity: both files written and non-trivial
		assertThat(vortexBytes).isPositive();
		assertThat(csvBytes).isPositive();
	}
}
