package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.IntArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ZigZagEncodingTest {

	private static final DType I32_DTYPE = new DType.Primitive(PType.I32, false);
	private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);

	private static DecodeContext buildI32Ctx(int[] encodedUnsigned) {
		ByteBuffer buf = ByteBuffer.allocate(encodedUnsigned.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int v : encodedUnsigned) {
			buf.putInt(v);
		}
		buf.flip();
		MemorySegment seg = MemorySegment.ofBuffer(buf);

		ArrayNode primitiveNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0}, null);
		ArrayNode zigzagNode = new ArrayNode(EncodingId.VORTEX_ZIGZAG, null, new ArrayNode[]{primitiveNode}, new int[0], null);

		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(new ZigZagEncoding());
		registry.register(new PrimitiveEncoding());
		return new DecodeContext(zigzagNode, I32_DTYPE, encodedUnsigned.length,
				new MemorySegment[]{seg}, registry, Arena.ofAuto());
	}

	static Stream<Arguments> i32Cases() {
		return Stream.of(
				// zigzag: 0→0, 1→-1, 2→1, 3→-2, 4→2
				Arguments.of("zeros", new int[]{0, 0, 0}, new int[]{0, 0, 0}),
				Arguments.of("mixed", new int[]{0, 1, 2, 3, 4}, new int[]{0, -1, 1, -2, 2}),
				Arguments.of("large", new int[]{Integer.MAX_VALUE & ~1, (Integer.MAX_VALUE & ~1) | 1},
						new int[]{Integer.MAX_VALUE / 2, Integer.MIN_VALUE / 2})
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("i32Cases")
	void decode_i32_zigzagDecodesCorrectly(String name, int[] encoded, int[] expected) {
		// Given
		DecodeContext ctx = buildI32Ctx(encoded);
		var sut = new ZigZagEncoding();

		// When
		var result = sut.decode(ctx);

		// Then
		assertThat(result).isInstanceOf(IntArray.class);
		assertThat(result.length()).isEqualTo(expected.length);
		MemorySegment seg = result.buffer(0);
		for (int i = 0; i < expected.length; i++) {
			assertThat(seg.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), (long) i * 4))
					.as("index %d", i).isEqualTo(expected[i]);
		}
	}

	@Test
	void decode_empty_returnsEmptyArray() {
		// Given
		DecodeContext ctx = buildI32Ctx(new int[]{});
		var sut = new ZigZagEncoding();

		// When
		var result = sut.decode(ctx);

		// Then
		assertThat(result.length()).isZero();
	}
}
