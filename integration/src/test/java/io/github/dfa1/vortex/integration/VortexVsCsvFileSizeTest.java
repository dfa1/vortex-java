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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Demonstrates file size difference between Vortex and plain CSV for OHLC data.
///
/// Same 100 000 rows written to both formats; sizes and ratio logged to stdout.
class VortexVsCsvFileSizeTest {

	private static final int TOTAL_ROWS = 100_000;
	private static final int BATCH_SIZE = 50_000;

	private static final DType.Struct SCHEMA = new DType.Struct(
			List.of("symbol", "date", "open", "high", "low", "close", "volume"),
			List.of(
					new DType.Utf8(false),
					new DType.Primitive(PType.I32, false),
					new DType.Primitive(PType.F64, false),
					new DType.Primitive(PType.F64, false),
					new DType.Primitive(PType.F64, false),
					new DType.Primitive(PType.F64, false),
					new DType.Primitive(PType.I64, false)
			),
			false
	);


	@Test
	void vortexSmallerThanCsv(@TempDir Path tmp) throws IOException {
		// Given
		List<OhlcGenerator.OhlcBatch> batches = OhlcGenerator.generate(TOTAL_ROWS, BATCH_SIZE);
		Path vortexFile = tmp.resolve("ohlc.vtx");
		Path csvFile    = tmp.resolve("ohlc.csv");

		// When — write Vortex with cascading (ALP+Bitpacked for F64, FoR+Bitpacked for I64, Dict+Bitpacked for symbol codes)
		try (FileChannel ch = FileChannel.open(vortexFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     VortexWriter writer = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(2))) {
			for (OhlcGenerator.OhlcBatch b : batches) {
				writer.writeChunk(Map.of(
						"symbol", b.symbols(), "date", b.dates(), "open", b.open(), "high", b.high(),
						"low", b.low(), "close", b.close(), "volume", b.volume()
				));
			}
		}

		// When — write CSV
		try (BufferedWriter csv = Files.newBufferedWriter(csvFile)) {
			csv.write("symbol,date,open,high,low,close,volume\n");
			for (OhlcGenerator.OhlcBatch b : batches) {
				for (int i = 0; i < b.dates().length; i++) {
					csv.write(b.symbols()[i] + "," + LocalDate.ofEpochDay(b.dates()[i]) + ","
							+ b.open()[i] + "," + b.high()[i] + ","
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

		// Vortex with cascading should beat CSV
		assertThat(vortexBytes)
				.as("Vortex with cascading (depth=2) should be smaller than CSV")
				.isLessThan(csvBytes);
	}
}
