package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ByteBoolEncodingTest {

	@Nested
	class Encode {

		@ParameterizedTest
		@MethodSource("boolArrays")
		void encodeDecode_isLossless(boolean[] data) {
			// Given
			var sut = new ByteBoolEncoding();
			EncodingRegistry registry = EncodingRegistry.empty();
			registry.register(sut);

			// When
			EncodeResult encoded = sut.encode(DTypes.BOOL, data, EncodeTestHelper.testCtx());
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.BOOL, registry);
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(data.length);
			BoolArray boolArr = (BoolArray) result;
			for (int i = 0; i < data.length; i++) {
				assertThat(boolArr.getBoolean(i)).as("index %d", i).isEqualTo(data[i]);
			}
		}

		static Stream<boolean[]> boolArrays() {
			return Stream.of(
					new boolean[]{},
					new boolean[]{false},
					new boolean[]{true},
					new boolean[]{true, false, true, false, true},
					new boolean[]{false, false, false, false},
					new boolean[]{true, true, true, true, true, true, true, true, true}
			);
		}
	}

	@Nested
	class Decode {

		@ParameterizedTest(name = "{0}")
		@MethodSource("cases")
		void decode_byteBool_packsToBitArray(String name, byte[] input, boolean[] expected) {
			// Given
			DecodeContext ctx = buildCtx(input);
			var sut = new ByteBoolEncoding();

			// When
			var result = sut.decode(ctx);

			// Then
			assertThat(result).isInstanceOf(BoolArray.class);
			assertThat(result.length()).isEqualTo(expected.length);
			BoolArray boolArr = (BoolArray) result;
			for (int i = 0; i < expected.length; i++) {
				assertThat(boolArr.getBoolean(i)).as("index %d", i).isEqualTo(expected[i]);
			}
		}

		static Stream<Arguments> cases() {
			return Stream.of(
					Arguments.of("all false", new byte[]{0, 0, 0}, new boolean[]{false, false, false}),
					Arguments.of("all true", new byte[]{1, 42, (byte) 0xFF}, new boolean[]{true, true, true}),
					Arguments.of("mixed", new byte[]{0, 1, 0, 1}, new boolean[]{false, true, false, true}),
					Arguments.of("empty", new byte[]{}, new boolean[]{})
			);
		}

		private static DecodeContext buildCtx(byte[] byteValues) {
			MemorySegment buf = MemorySegment.ofArray(byteValues);
			ArrayNode node = ArrayNode.of(EncodingId.VORTEX_BYTEBOOL, null, new ArrayNode[0], new int[]{0}, null);
			EncodingRegistry registry = EncodingRegistry.empty();
			registry.register(new ByteBoolEncoding());
			return new DecodeContext(node, DTypes.BOOL, byteValues.length, new MemorySegment[]{buf}, registry,
					Arena.ofAuto());
		}
	}
}
