package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.LongArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class ExtEncodingTest {

	private static final ValueLayout.OfLong LE_LONG =
			ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	@Test
	void decode_extensionWrappingI64_returnsStorageArray() {
		// Given
		long n = 4;
		long[] values = {10L, 20L, 30L, 40L};

		// Build a raw I64 buffer
		MemorySegment buf = Arena.ofAuto().allocate(n * Long.BYTES, 8);
		for (int i = 0; i < n; i++) {
			buf.setAtIndex(LE_LONG, i, values[i]);
		}

		DType storageDType = new DType.Primitive(PType.I64, false);
		DType extDType = new DType.Extension("vortex.timestamp", storageDType, null, false);

		// child node: vortex.primitive with buffer index 0
		ArrayNode primitiveNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0}, null);
		// parent node: vortex.ext, no buffers, one child
		ArrayNode extNode = new ArrayNode(EncodingId.VORTEX_EXT, null, new ArrayNode[]{primitiveNode}, new int[0], null);

		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(new PrimitiveEncoding());
		registry.register(new ExtEncoding());

		DecodeContext ctx = new DecodeContext(
				extNode, extDType, n, new MemorySegment[]{buf}, registry, Arena.ofAuto());

		var sut = new ExtEncoding();

		// When
		var result = sut.decode(ctx);

		// Then
		assertThat(result).isInstanceOf(LongArray.class);
		assertThat(result.length()).isEqualTo(n);
		for (int i = 0; i < n; i++) {
			assertThat(result.getLong(i)).isEqualTo(values[i]);
		}
	}
}
