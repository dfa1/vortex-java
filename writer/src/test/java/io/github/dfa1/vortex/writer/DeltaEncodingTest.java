package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.encoding.DeltaEncoding;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.scan.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeltaEncodingTest {

	private static final DType.Struct I64_SCHEMA = new DType.Struct(
			List.of("ts"),
			List.of(new DType.Primitive(PType.I64, false)),
			false);

	private static List<ScanResult> scanAll(VortexReader vf) throws IOException {
		var results = new ArrayList<ScanResult>();
		var iter = vf.scan(ScanOptions.all());
		while (iter.hasNext()) {
			results.add(iter.next());
		}
		return results;
	}

	private static EncodingRegistry deltaRegistry() {
		var registry = EncodingRegistry.empty();
		registry.register(new DeltaEncoding());
		registry.register(new io.github.dfa1.vortex.encoding.PrimitiveEncoding());
		return registry;
	}

	@Test
	void roundTrip_monotonicIncreasing(@TempDir Path tmp) throws IOException {
		// Given
		Path file = tmp.resolve("delta_inc.vtx");
		long[] data = {100L, 105L, 110L, 115L, 120L};

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
					 List.of(new DeltaEncoding()))) {
			// When
			sut.writeChunk(Map.of("ts", data));
		}

		// Then
		try (var vf = VortexReader.open(file, deltaRegistry())) {
			List<ScanResult> results = scanAll(vf);
			assertThat(results).hasSize(1);
			Array arr = results.getFirst().columns().get("ts");
			assertThat(arr.length()).isEqualTo(5L);
			var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < data.length; i++) {
				assertThat(arr.buffer(0).get(layout, (long) i * 8)).isEqualTo(data[i]);
			}
		}
	}

	@Test
	void roundTrip_monotonicDecreasing(@TempDir Path tmp) throws IOException {
		// Given
		Path file = tmp.resolve("delta_dec.vtx");
		long[] data = {1000L, 990L, 975L, 950L};

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
					 List.of(new DeltaEncoding()))) {
			// When
			sut.writeChunk(Map.of("ts", data));
		}

		// Then
		try (var vf = VortexReader.open(file, deltaRegistry())) {
			List<ScanResult> results = scanAll(vf);
			Array arr = results.getFirst().columns().get("ts");
			var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < data.length; i++) {
				assertThat(arr.buffer(0).get(layout, (long) i * 8)).isEqualTo(data[i]);
			}
		}
	}

	@Test
	void roundTrip_allSameValue(@TempDir Path tmp) throws IOException {
		// Given — deltas all zero, bit_width=0
		Path file = tmp.resolve("delta_same.vtx");
		long[] data = {42L, 42L, 42L, 42L};

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
					 List.of(new DeltaEncoding()))) {
			// When
			sut.writeChunk(Map.of("ts", data));
		}

		// Then
		try (var vf = VortexReader.open(file, deltaRegistry())) {
			List<ScanResult> results = scanAll(vf);
			Array arr = results.getFirst().columns().get("ts");
			var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < data.length; i++) {
				assertThat(arr.buffer(0).get(layout, (long) i * 8)).isEqualTo(42L);
			}
		}
	}

	@Test
	void roundTrip_singleElement(@TempDir Path tmp) throws IOException {
		// Given — no deltas to store
		Path file = tmp.resolve("delta_single.vtx");
		long[] data = {99L};

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
					 List.of(new DeltaEncoding()))) {
			// When
			sut.writeChunk(Map.of("ts", data));
		}

		// Then
		try (var vf = VortexReader.open(file, deltaRegistry())) {
			List<ScanResult> results = scanAll(vf);
			Array arr = results.getFirst().columns().get("ts");
			assertThat(arr.length()).isEqualTo(1L);
			var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			assertThat(arr.buffer(0).get(layout, 0L)).isEqualTo(99L);
		}
	}

	@Test
	void roundTrip_mixedDeltas(@TempDir Path tmp) throws IOException {
		// Given — non-monotonic; some deltas negative
		Path file = tmp.resolve("delta_mixed.vtx");
		long[] data = {0L, 5L, 7L, 12L, 11L, 15L};

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
					 List.of(new DeltaEncoding()))) {
			// When
			sut.writeChunk(Map.of("ts", data));
		}

		// Then
		try (var vf = VortexReader.open(file, deltaRegistry())) {
			List<ScanResult> results = scanAll(vf);
			Array arr = results.getFirst().columns().get("ts");
			var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < data.length; i++) {
				assertThat(arr.buffer(0).get(layout, (long) i * 8)).isEqualTo(data[i]);
			}
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	@Test
	void roundTrip_multipleChunks(@TempDir Path tmp) throws IOException {
		// Given
		Path file = tmp.resolve("delta_multi.vtx");
		long[] chunk1 = {1000L, 1001L, 1002L};
		long[] chunk2 = {2000L, 2005L, 2010L};

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
					 List.of(new DeltaEncoding()))) {
			// When
			sut.writeChunk(Map.of("ts", chunk1));
			sut.writeChunk(Map.of("ts", chunk2));
		}

		// Then — process each chunk before advancing; hasNext() closes the previous chunk's arena
		try (var vf = VortexReader.open(file, deltaRegistry());
		     var iter = vf.scan(ScanOptions.all())) {
			var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

			assertThat(iter.hasNext()).isTrue();
			Array a1 = iter.next().columns().get("ts");
			for (int i = 0; i < chunk1.length; i++) {
				assertThat(a1.buffer(0).get(layout, (long) i * 8)).isEqualTo(chunk1[i]);
			}

			assertThat(iter.hasNext()).isTrue();
			Array a2 = iter.next().columns().get("ts");
			for (int i = 0; i < chunk2.length; i++) {
				assertThat(a2.buffer(0).get(layout, (long) i * 8)).isEqualTo(chunk2[i]);
			}

			assertThat(iter.hasNext()).isFalse();
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {2, 10, 100, 1000})
	void roundTrip_sequentialTimestamps(int n, @TempDir Path tmp) throws IOException {
		// Given — simulate monotonic timestamp column
		Path file = tmp.resolve("delta_seq_" + n + ".vtx");
		long[] data = new long[n];
		for (int i = 0; i < n; i++) {
			data[i] = 1_700_000_000L + i * 1000L;
		}

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I64_SCHEMA, WriteOptions.defaults(),
					 List.of(new DeltaEncoding()))) {
			// When
			sut.writeChunk(Map.of("ts", data));
		}

		// Then
		try (var vf = VortexReader.open(file, deltaRegistry())) {
			List<ScanResult> results = scanAll(vf);
			assertThat(results).hasSize(1);
			Array arr = results.getFirst().columns().get("ts");
			assertThat(arr.length()).isEqualTo((long) n);
			var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < n; i++) {
				assertThat(arr.buffer(0).get(layout, (long) i * 8))
						.as("index %d", i)
						.isEqualTo(data[i]);
			}
		}
	}
}
