package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.IntArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ZigZagEncodingTest {


	@Nested
	class Decode {

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
				assertThat(seg.get(PTypeIO.LE_INT, (long) i * 4))
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

		static Stream<Arguments> i32Cases() {
			return Stream.of(
					// zigzag: 0→0, 1→-1, 2→1, 3→-2, 4→2
					Arguments.of("zeros", new int[]{0, 0, 0}, new int[]{0, 0, 0}),
					Arguments.of("mixed", new int[]{0, 1, 2, 3, 4}, new int[]{0, -1, 1, -2, 2}),
					Arguments.of("large", new int[]{Integer.MAX_VALUE & ~1, (Integer.MAX_VALUE & ~1) | 1},
							new int[]{Integer.MAX_VALUE / 2, Integer.MIN_VALUE / 2})
			);
		}

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
			return new DecodeContext(zigzagNode, DTypes.I32, encodedUnsigned.length,
					new MemorySegment[]{seg}, registry, Arena.ofAuto());
		}
	}

	@Nested
	class Encode {

		@ParameterizedTest
		@MethodSource("i32RoundtripArrays")
		void encodeDecode_i32_isLossless(int[] data) {
			// Given
			var sut = new ZigZagEncoding();
			EncodingRegistry registry = EncodingRegistry.empty();
			registry.register(sut);
			registry.register(new PrimitiveEncoding());
			var le = PTypeIO.LE_INT;

			// When
			EncodeResult encoded = sut.encode(DTypes.I32, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I32, registry);
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(data.length);
			for (int i = 0; i < data.length; i++) {
				assertThat(result.buffer(0).get(le, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
			}
		}

		@ParameterizedTest
		@MethodSource("i64RoundtripArrays")
		void encodeDecode_i64_isLossless(long[] data) {
			// Given
			var sut = new ZigZagEncoding();
			EncodingRegistry registry = EncodingRegistry.empty();
			registry.register(sut);
			registry.register(new PrimitiveEncoding());
			var le = PTypeIO.LE_LONG;

			// When
			EncodeResult encoded = sut.encode(DTypes.I64, data);
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I64, registry);
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(data.length);
			for (int i = 0; i < data.length; i++) {
				assertThat(result.buffer(0).get(le, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
			}
		}

		static Stream<int[]> i32RoundtripArrays() {
			return Stream.of(
					new int[]{},
					new int[]{0},
					new int[]{-1, 1, -2, 2},
					new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, 0},
					new int[]{-100, 0, 100, -1000, 1000}
			);
		}

		static Stream<long[]> i64RoundtripArrays() {
			return Stream.of(
					new long[]{},
					new long[]{0L},
					new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 0L},
					new long[]{-1L, 1L, -2L, 2L}
			);
		}
	}
}
