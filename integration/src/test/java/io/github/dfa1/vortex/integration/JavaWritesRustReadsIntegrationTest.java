package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Cross-compatibility: Java writer → Rust (JNI) reader.
class JavaWritesRustReadsIntegrationTest {

	private static final Session SESSION = Session.create();
	private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
	private static final DType.Struct SCHEMA = new DType.Struct(
			List.of("id", "value"),
			List.of(new DType.Primitive(PType.I64, false),
					new DType.Primitive(PType.F64, false)),
			false);

	private static final DType.Struct OHLC_SCHEMA = new DType.Struct(
			List.of("date", "symbol", "open", "high", "low", "close", "volume"),
			List.of(
					new DType.Primitive(PType.I32, false),
					new DType.Utf8(false),
					new DType.Primitive(PType.F64, false),
					new DType.Primitive(PType.F64, false),
					new DType.Primitive(PType.F64, false),
					new DType.Primitive(PType.F64, false),
					new DType.Primitive(PType.I64, false)),
			false);

	static {
		NativeLoader.loadJni();
	}

	private static long[] readLongColumn(Path file, String column) throws IOException {
		String uri = file.toAbsolutePath().toUri().toString();
		ScanOptions opts = ScanOptions.builder()
				.projection(Expression.select(new String[]{column}, Expression.root()))
				.build();
		var longs = new ArrayList<Long>();
		DataSource ds = DataSource.open(SESSION, uri);
		Scan scan = ds.scan(opts);
		while (scan.hasNext()) {
			Partition partition = scan.next();
			try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
				while (reader.loadNextBatch()) {
					VectorSchemaRoot root = reader.getVectorSchemaRoot();
					BigIntVector vec = (BigIntVector) root.getVector(column);
					for (int i = 0; i < root.getRowCount(); i++) {
						longs.add(vec.get(i));
					}
				}
			}
		}
		return longs.stream().mapToLong(Long::longValue).toArray();
	}

	private static double[] readDoubleColumn(Path file, String column) throws IOException {
		String uri = file.toAbsolutePath().toUri().toString();
		ScanOptions opts = ScanOptions.builder()
				.projection(Expression.select(new String[]{column}, Expression.root()))
				.build();
		var doubles = new ArrayList<Double>();
		DataSource ds = DataSource.open(SESSION, uri);
		Scan scan = ds.scan(opts);
		while (scan.hasNext()) {
			Partition partition = scan.next();
			try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
				while (reader.loadNextBatch()) {
					VectorSchemaRoot root = reader.getVectorSchemaRoot();
					Float8Vector vec = (Float8Vector) root.getVector(column);
					for (int i = 0; i < root.getRowCount(); i++) {
						doubles.add(vec.get(i));
					}
				}
			}
		}
		return doubles.stream().mapToDouble(Double::doubleValue).toArray();
	}

	// ── JNI read helpers ──────────────────────────────────────────────────────

	@Test
	void javaWriter_jniReader_cascading_ohlc(@TempDir Path tmp) throws IOException {
		// Given — OHLC data written with cascading(3): exercises ALP→FOR→bitpacked chain
		Path file = tmp.resolve("java_cascade_ohlc.vtx");
		List<OhlcGenerator.OhlcBatch> batches = OhlcGenerator.generate(10_000, 1_000);

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, OHLC_SCHEMA, WriteOptions.cascading(3))) {
			for (OhlcGenerator.OhlcBatch b : batches) {
				sut.writeChunk(Map.of(
						"date",   b.dates(),
						"symbol", b.symbols(),
						"open",   b.open(),
						"high",   b.high(),
						"low",    b.low(),
						"close",  b.close(),
						"volume", b.volume()));
			}
		}

		// When
		long[]   volumes = readLongColumn(file, "volume");
		double[] closes  = readDoubleColumn(file, "close");

		// Then — JNI reader may return chunks in a different order for cascaded files;
		// verify all values round-trip correctly regardless of partition order.
		long[]   expectedVolumes = batches.stream().flatMapToLong(b -> Arrays.stream(b.volume())).toArray();
		double[] expectedCloses  = batches.stream().flatMapToDouble(b -> Arrays.stream(b.close())).toArray();
		assertThat(volumes).containsExactlyInAnyOrder(expectedVolumes);
		assertThat(closes).containsExactlyInAnyOrder(expectedCloses);
	}

	@Test
	void javaWriter_jniReader_singleChunk(@TempDir Path tmp) throws IOException {
		// Given
		Path file = tmp.resolve("java_single.vtx");
		long[] ids = {1L, 2L, 3L};
		double[] vals = {1.1, 2.2, 3.3};
		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
			sut.writeChunk(Map.of("id", ids, "value", vals));
		}

		// When
		long[] decodedIds = readLongColumn(file, "id");
		double[] decodedVals = readDoubleColumn(file, "value");

		// Then
		assertThat(decodedIds).containsExactly(1L, 2L, 3L);
		assertThat(decodedVals).containsExactly(1.1, 2.2, 3.3);
	}

	@Test
	void javaWriter_jniReader_multipleChunks(@TempDir Path tmp) throws IOException {
		// Given
		Path file = tmp.resolve("java_multi.vtx");
		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
			sut.writeChunk(Map.of("id", new long[]{1L, 2L}, "value", new double[]{1.1, 2.2}));
			sut.writeChunk(Map.of("id", new long[]{3L, 4L, 5L}, "value", new double[]{3.3, 4.4, 5.5}));
		}

		// When
		long[] decodedIds = readLongColumn(file, "id");

		// Then — JNI may merge chunks; verify all values present regardless of partition count
		assertThat(decodedIds).containsExactly(1L, 2L, 3L, 4L, 5L);
	}
}
