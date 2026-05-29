package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.VarBinArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class VarBinViewEncodingTest {

	private static final ValueLayout.OfInt  LE_INT  = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final DType UTF8 = new DType.Utf8(false);

	// Writes a 16-byte inlined view: [size:u32 | data:12bytes]
	private static void writeInlined(MemorySegment views, long viewIdx, byte[] value) {
		long off = viewIdx * 16;
		views.set(LE_INT, off, value.length);
		MemorySegment.copy(MemorySegment.ofArray(value), 0, views, off + 4, value.length);
	}

	// Writes a 16-byte reference view: [size:u32 | prefix:4bytes | buffer_index:u32 | offset:u32]
	private static void writeRef(MemorySegment views, long viewIdx,
	                              int size, int bufferIndex, int offset) {
		long off = viewIdx * 16;
		views.set(LE_INT, off,      size);
		views.set(LE_INT, off + 8,  bufferIndex);
		views.set(LE_INT, off + 12, offset);
	}

	@Test
	void decode_inlinedValues_returnsCorrectStrings() {
		// Given — 3 short strings, all ≤12 bytes → inlined
		String[] words = {"hello", "world", "hi"};
		long n = words.length;

		Arena arena = Arena.ofAuto();
		MemorySegment views = arena.allocate(n * 16);
		for (int i = 0; i < n; i++) {
			writeInlined(views, i, words[i].getBytes(StandardCharsets.UTF_8));
		}

		// No data buffers; views is buffer[0] (only buffer)
		ArrayNode node = new ArrayNode(EncodingId.VORTEX_VARBINVIEW, null,
				new ArrayNode[0], new int[]{0}, null);

		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(new VarBinViewEncoding());

		DecodeContext ctx = new DecodeContext(node, UTF8, n,
				new MemorySegment[]{views}, registry, arena);
		var sut = new VarBinViewEncoding();

		// When
		var result = sut.decode(ctx);

		// Then
		assertThat(result).isInstanceOf(VarBinArray.class);
		assertThat(result.length()).isEqualTo(n);
		for (int i = 0; i < n; i++) {
			assertThat(new String(result.getBytes(i), StandardCharsets.UTF_8)).isEqualTo(words[i]);
		}
	}

	@Test
	void decode_referenceValues_returnsCorrectStrings() {
		// Given — one long string (>12 bytes) stored in an external data buffer
		String longStr = "a longer string here";
		byte[] longBytes = longStr.getBytes(StandardCharsets.UTF_8);
		long n = 1;

		Arena arena = Arena.ofAuto();

		// data buffer (buffer index 0 in segment array)
		MemorySegment dataBuf = arena.allocate(longBytes.length);
		MemorySegment.copy(MemorySegment.ofArray(longBytes), 0, dataBuf, 0, longBytes.length);

		// views buffer (buffer index 1 in segment array — last)
		MemorySegment views = arena.allocate(n * 16);
		writeRef(views, 0, longBytes.length, 0, 0); // buffer_index=0, offset=0

		// bufferIndices: [0=dataBuf, 1=views] — views is last
		ArrayNode node = new ArrayNode(EncodingId.VORTEX_VARBINVIEW, null,
				new ArrayNode[0], new int[]{0, 1}, null);

		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(new VarBinViewEncoding());

		DecodeContext ctx = new DecodeContext(node, UTF8, n,
				new MemorySegment[]{dataBuf, views}, registry, arena);
		var sut = new VarBinViewEncoding();

		// When
		var result = sut.decode(ctx);

		// Then
		assertThat(result).isInstanceOf(VarBinArray.class);
		assertThat(result.length()).isEqualTo(n);
		assertThat(new String(result.getBytes(0), StandardCharsets.UTF_8)).isEqualTo(longStr);
	}

	@Test
	void decode_mixedValues_returnsAllStrings() {
		// Given — mix of inlined and reference values
		String shortStr = "short";
		String longStr  = "this is definitely longer than twelve bytes";
		long n = 2;

		Arena arena = Arena.ofAuto();
		byte[] longBytes = longStr.getBytes(StandardCharsets.UTF_8);

		MemorySegment dataBuf = arena.allocate(longBytes.length);
		MemorySegment.copy(MemorySegment.ofArray(longBytes), 0, dataBuf, 0, longBytes.length);

		MemorySegment views = arena.allocate(n * 16);
		writeInlined(views, 0, shortStr.getBytes(StandardCharsets.UTF_8));
		writeRef(views, 1, longBytes.length, 0, 0);

		// bufferIndices: [0=dataBuf, 1=views]
		ArrayNode node = new ArrayNode(EncodingId.VORTEX_VARBINVIEW, null,
				new ArrayNode[0], new int[]{0, 1}, null);

		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(new VarBinViewEncoding());

		DecodeContext ctx = new DecodeContext(node, UTF8, n,
				new MemorySegment[]{dataBuf, views}, registry, arena);
		var sut = new VarBinViewEncoding();

		// When
		var result = sut.decode(ctx);

		// Then
		assertThat(result.length()).isEqualTo(n);
		assertThat(new String(result.getBytes(0), StandardCharsets.UTF_8)).isEqualTo(shortStr);
		assertThat(new String(result.getBytes(1), StandardCharsets.UTF_8)).isEqualTo(longStr);
	}
}
