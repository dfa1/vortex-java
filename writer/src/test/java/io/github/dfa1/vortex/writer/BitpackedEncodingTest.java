package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.BitpackedEncoding;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
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

class BitpackedEncodingTest {

	private static final DType.Struct I32_SCHEMA = new DType.Struct(
			List.of("value"),
			List.of(new DType.Primitive(PType.I32, false)),
			false);

	private static List<ScanResult> scanAll(VortexReader vf) throws IOException {
		var results = new ArrayList<ScanResult>();
		var iter = vf.scan(ScanOptions.all());
		while (iter.hasNext()) {
			results.add(iter.next());
		}
		return results;
	}

	private static EncodingRegistry bpRegistry() {
		var registry = EncodingRegistry.empty();
		registry.register(new BitpackedEncoding());
		return registry;
	}

	@Test
	void roundTrip_positiveIntegers(@TempDir Path tmp) throws IOException {
		// Given
		Path file = tmp.resolve("bp.vtx");
		int[] data = {0, 1, 2, 3, 4, 5, 6, 7};

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
					 List.of(new BitpackedEncoding()))) {
			// When
			sut.writeChunk(Map.of("value", data));
		}

		// Then
		try (var vf = VortexReader.open(file, bpRegistry())) {
			List<ScanResult> results = scanAll(vf);
			assertThat(results).hasSize(1);
			Array arr = results.get(0).columns().get("value");
			assertThat(arr.length()).isEqualTo(8L);
			var layout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < data.length; i++) {
				assertThat(arr.buffer(0).get(layout, (long) i * 4)).isEqualTo(data[i]);
			}
		}
	}

	@Test
	void roundTrip_allSameValue(@TempDir Path tmp) throws IOException {
		// Given — all identical values
		Path file = tmp.resolve("bp_same.vtx");
		int[] data = {42, 42, 42, 42};

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
					 List.of(new BitpackedEncoding()))) {
			// When
			sut.writeChunk(Map.of("value", data));
		}

		// Then
		try (var vf = VortexReader.open(file, bpRegistry())) {
			List<ScanResult> results = scanAll(vf);
			assertThat(results).hasSize(1);
			Array arr = results.get(0).columns().get("value");
			var layout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < data.length; i++) {
				assertThat(arr.buffer(0).get(layout, (long) i * 4)).isEqualTo(42);
			}
		}
	}

	@Test
	void roundTrip_negativeIntegers(@TempDir Path tmp) throws IOException {
		// Given — frame-of-reference shifts negative values
		Path file = tmp.resolve("bp_neg.vtx");
		int[] data = {-10, -5, 0, 5, 10};

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
					 List.of(new BitpackedEncoding()))) {
			// When
			sut.writeChunk(Map.of("value", data));
		}

		// Then
		try (var vf = VortexReader.open(file, bpRegistry())) {
			List<ScanResult> results = scanAll(vf);
			assertThat(results).hasSize(1);
			Array arr = results.get(0).columns().get("value");
			var layout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < data.length; i++) {
				assertThat(arr.buffer(0).get(layout, (long) i * 4)).isEqualTo(data[i]);
			}
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	@Test
	void roundTrip_multipleChunks(@TempDir Path tmp) throws IOException {
		// Given
		Path file = tmp.resolve("bp_multi.vtx");
		int[] chunk1 = {100, 200, 150};
		int[] chunk2 = {300, 400, 350};

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
					 List.of(new BitpackedEncoding()))) {
			// When
			sut.writeChunk(Map.of("value", chunk1));
			sut.writeChunk(Map.of("value", chunk2));
		}

		// Then
		try (var vf = VortexReader.open(file, bpRegistry())) {
			List<ScanResult> results = scanAll(vf);
			assertThat(results).hasSize(2);
			var layout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

			Array a1 = results.get(0).columns().get("value");
			assertThat(a1.length()).isEqualTo(3L);
			for (int i = 0; i < chunk1.length; i++) {
				assertThat(a1.buffer(0).get(layout, (long) i * 4)).isEqualTo(chunk1[i]);
			}

			Array a2 = results.get(1).columns().get("value");
			assertThat(a2.length()).isEqualTo(3L);
			for (int i = 0; i < chunk2.length; i++) {
				assertThat(a2.buffer(0).get(layout, (long) i * 4)).isEqualTo(chunk2[i]);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 4, 7, 8, 15, 16, 31})
	void roundTrip_bitWidths(int maxVal, @TempDir Path tmp) throws IOException {
		// Given — verify correct round-trip for various bit widths
		Path file = tmp.resolve("bp_bw_" + maxVal + ".vtx");
		int[] data = new int[maxVal + 1];
		for (int i = 0; i <= maxVal; i++) {
			data[i] = i;
		}

		try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		     var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
					 List.of(new BitpackedEncoding()))) {
			// When
			sut.writeChunk(Map.of("value", data));
		}

		// Then
		try (var vf = VortexReader.open(file, bpRegistry())) {
			List<ScanResult> results = scanAll(vf);
			assertThat(results).hasSize(1);
			Array arr = results.get(0).columns().get("value");
			var layout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < data.length; i++) {
				assertThat(arr.buffer(0).get(layout, (long) i * 4))
						.as("value at index %d", i)
						.isEqualTo(data[i]);
			}
		}
	}
}
