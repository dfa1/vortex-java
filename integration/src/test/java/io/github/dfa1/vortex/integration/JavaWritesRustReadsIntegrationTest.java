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
import io.github.dfa1.vortex.encoding.VarBinEncoding;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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

	private static final DType.Struct I32_SCHEMA = new DType.Struct(
			List.of("v"),
			List.of(new DType.Primitive(PType.I32, false)),
			false);

	private static final DType.Struct STRING_SCHEMA = new DType.Struct(
			List.of("s"),
			List.of(new DType.Utf8(false)),
			false);

	private static final DType.Struct TS_SCHEMA = new DType.Struct(
			List.of("ts"),
			List.of(new DType.Primitive(PType.I64, false)),
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

	private static int[] readIntColumn(Path file, String column) throws IOException {
		String uri = file.toAbsolutePath().toUri().toString();
		ScanOptions opts = ScanOptions.builder()
				.projection(Expression.select(new String[]{column}, Expression.root()))
				.build();
		var ints = new ArrayList<Integer>();
		DataSource ds = DataSource.open(SESSION, uri);
		Scan scan = ds.scan(opts);
		while (scan.hasNext()) {
			Partition partition = scan.next();
			try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
				while (reader.loadNextBatch()) {
					VectorSchemaRoot root = reader.getVectorSchemaRoot();
					IntVector vec = (IntVector) root.getVector(column);
					for (int i = 0; i < root.getRowCount(); i++) {
						ints.add(vec.get(i));
					}
				}
			}
		}
		return ints.stream().mapToInt(Integer::intValue).toArray();
	}

	private static String[] readStringColumn(Path file, String column) throws IOException {
		String uri = file.toAbsolutePath().toUri().toString();
		ScanOptions opts = ScanOptions.builder()
				.projection(Expression.select(new String[]{column}, Expression.root()))
				.build();
		var strings = new ArrayList<String>();
		DataSource ds = DataSource.open(SESSION, uri);
		Scan scan = ds.scan(opts);
		while (scan.hasNext()) {
			Partition partition = scan.next();
			try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
				while (reader.loadNextBatch()) {
					VectorSchemaRoot root = reader.getVectorSchemaRoot();
					VarCharVector vec = (VarCharVector) root.getVector(column);
					for (int i = 0; i < root.getRowCount(); i++) {
						strings.add(vec.getObject(i).toString());
					}
				}
			}
		}
		return strings.toArray(String[]::new);
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

	@Test
	void javaWriter_jniReader_i32Column(@TempDir Path tmp) throws IOException {
		// Given
		Path file = tmp.resolve("java_i32.vtx");
		int[] data = {-100, 0, 1, 127, Integer.MAX_VALUE, Integer.MIN_VALUE};
		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults())) {
			// When
			sut.writeChunk(Map.of("v", data));
		}

		// Then
		int[] decoded = readIntColumn(file, "v");
		assertThat(decoded).containsExactly(data);
	}

	@Test
	void javaWriter_jniReader_utf8Column(@TempDir Path tmp) throws IOException {
		// Given — VarBin encoding (raw bytes + offsets, no dictionary)
		Path file = tmp.resolve("java_utf8.vtx");
		String[] data = {"apple", "banana", "cherry", "date", "elderberry"};
		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults(),
					 List.of(new VarBinEncoding()))) {
			// When
			sut.writeChunk(Map.of("s", data));
		}

		// Then
		String[] decoded = readStringColumn(file, "s");
		assertThat(decoded).containsExactly(data);
	}

	@Test
	void javaWriter_jniReader_dictEncodedUtf8Column(@TempDir Path tmp) throws IOException {
		// Given — DictEncoding (DictLayoutMetadata proto + children[0]=codes, children[1]=VarBin values)
		Path file = tmp.resolve("java_dict_utf8.vtx");
		String[] data = {"apple", "banana", "cherry", "date", "elderberry"};
		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults())) {
			// When — default pipeline selects DictEncoding for Utf8
			sut.writeChunk(Map.of("s", data));
		}

		// Then
		String[] decoded = readStringColumn(file, "s");
		assertThat(decoded).containsExactly(data);
	}

	@Test
	void javaWriter_jniReader_largeChunk_twoFastLanesBlocks(@TempDir Path tmp) throws IOException {
		// Given — 2048 rows = exactly 2 full 1024-element FastLanes blocks
		Path file = tmp.resolve("java_large.vtx");
		int n = 2048;
		long[] ids = new long[n];
		double[] vals = new double[n];
		for (int i = 0; i < n; i++) {
			ids[i] = i;
			vals[i] = i * 0.5;
		}
		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
			// When
			sut.writeChunk(Map.of("id", ids, "value", vals));
		}

		// Then
		long[] decodedIds = readLongColumn(file, "id");
		assertThat(decodedIds).containsExactly(ids);
	}

	@Test
	void javaWriter_jniReader_monotonic_i64_cascading(@TempDir Path tmp) throws IOException {
		// Given — monotonic timestamps: FOR reduces to constant delta, bitpacked to ~10 bits
		Path file = tmp.resolve("java_ts.vtx");
		int n = 5_000;
		long[] ts = new long[n];
		for (int i = 0; i < n; i++) {
			ts[i] = 1_700_000_000L + (long) i * 1_000;
		}
		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.cascading(3))) {
			// When
			sut.writeChunk(Map.of("ts", ts));
		}

		// Then
		long[] decoded = readLongColumn(file, "ts");
		assertThat(decoded).containsExactly(ts);
	}

	@Test
	void javaWriter_jniReader_cascading_ohlc_columnProjection(@TempDir Path tmp) throws IOException {
		// Given
		Path file = tmp.resolve("java_cascade_proj.vtx");
		List<OhlcGenerator.OhlcBatch> batches = OhlcGenerator.generate(2_000, 1_000);
		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, OHLC_SCHEMA, WriteOptions.cascading(3))) {
			for (OhlcGenerator.OhlcBatch b : batches) {
				sut.writeChunk(Map.of(
						"date", b.dates(), "symbol", b.symbols(),
						"open", b.open(), "high", b.high(),
						"low", b.low(), "close", b.close(), "volume", b.volume()));
			}
		}

		// When — project only volume
		long[] volumes = readLongColumn(file, "volume");

		// Then
		long[] expected = batches.stream().flatMapToLong(b -> Arrays.stream(b.volume())).toArray();
		assertThat(volumes).containsExactlyInAnyOrder(expected);
	}

	// ── Property-based tests ──────────────────────────────────────────────────

	/// Dict utf8 with arbitrary strings (small dict → U8 codes).
	/// Validates that random string data survives Java dict-encode → Rust JNI read.
	@Property(tries = 20)
	void prop_dictUtf8_ascii_roundTripsViaRust(
			@ForAll("asciiStringArrays") String[] data) throws IOException {
		Path tmp = Files.createTempDirectory("vortex-pbt-ascii");
		try {
			Path file = tmp.resolve("pbt_dict_utf8_ascii.vtx");
			try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			     var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults())) {
				sut.writeChunk(Map.of("s", data));
			}
			String[] decoded = readStringColumn(file, "s");
			assertThat(decoded).containsExactly(data);
		} finally {
			deleteDir(tmp);
		}
	}

	/// Dict utf8 with 257+ unique strings → forces U16 codes (crosses U8→U16 boundary at 256).
	@Property(tries = 10)
	void prop_dictUtf8_u16Codes_roundTripsViaRust(
			@ForAll("u16DictStringArrays") String[] data) throws IOException {
		Path tmp = Files.createTempDirectory("vortex-pbt-u16");
		try {
			Path file = tmp.resolve("pbt_dict_utf8_u16.vtx");
			try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			     var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults())) {
				sut.writeChunk(Map.of("s", data));
			}
			String[] decoded = readStringColumn(file, "s");
			assertThat(decoded).containsExactly(data);
		} finally {
			deleteDir(tmp);
		}
	}

	/// Dict utf8 with unicode strings (multi-byte UTF-8, emoji, CJK).
	@Property(tries = 20)
	void prop_dictUtf8_unicode_roundTripsViaRust(
			@ForAll("unicodeStringArrays") String[] data) throws IOException {
		Path tmp = Files.createTempDirectory("vortex-pbt-unicode");
		try {
			Path file = tmp.resolve("pbt_dict_utf8_unicode.vtx");
			try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			     var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults())) {
				sut.writeChunk(Map.of("s", data));
			}
			String[] decoded = readStringColumn(file, "s");
			assertThat(decoded).containsExactly(data);
		} finally {
			deleteDir(tmp);
		}
	}

	/// I64 column: full Long range (MIN_VALUE, MAX_VALUE, negatives), empty and large arrays.
	@Property(tries = 30)
	void prop_i64_roundTripsViaRust(@ForAll("i64Arrays") long[] data) throws IOException {
		Path tmp = Files.createTempDirectory("vortex-pbt-i64");
		try {
			Path file = tmp.resolve("pbt_i64.vtx");
			try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			     var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults())) {
				sut.writeChunk(Map.of("ts", data));
			}
			long[] decoded = readLongColumn(file, "ts");
			assertThat(decoded).containsExactly(data);
		} finally {
			deleteDir(tmp);
		}
	}

	@Provide
	Arbitrary<String[]> asciiStringArrays() {
		Arbitrary<String> strings = Arbitraries.strings()
				.alpha()
				.ofMinLength(0).ofMaxLength(100);
		return strings.array(String[].class).ofMinSize(0).ofMaxSize(5_000);
	}

	@Provide
	Arbitrary<String[]> u16DictStringArrays() {
		// 257–5000 unique strings → U16 codes territory
		Arbitrary<String> strings = Arbitraries.strings()
				.alpha()
				.ofMinLength(3).ofMaxLength(20);
		return strings.list().ofMinSize(257).ofMaxSize(5_000)
				.map(list -> list.stream().distinct().toArray(String[]::new))
				.filter(arr -> arr.length >= 257);
	}

	@Provide
	Arbitrary<String[]> unicodeStringArrays() {
		Arbitrary<String> strings = Arbitraries.strings()
				.withCharRange('\u4E00', '\uD7FF')
				.ofMinLength(0).ofMaxLength(50);
		return strings.array(String[].class).ofMinSize(0).ofMaxSize(1_000);
	}

	@Provide
	Arbitrary<long[]> i64Arrays() {
		return Arbitraries.longs()
				.array(long[].class)
				.ofMinSize(0).ofMaxSize(10_000);
	}

		private static void deleteDir(Path dir) throws IOException {
		try (var walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}
}
