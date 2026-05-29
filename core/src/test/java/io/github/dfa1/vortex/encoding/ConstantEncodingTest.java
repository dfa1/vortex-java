package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: encode then decode is lossless for constant (all-equal) arrays.
class ConstantEncodingTest {

	private static final DType I32_DTYPE = new DType.Primitive(PType.I32, false);
	private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);

	static Stream<Arguments> i32ConstantArrays() {
		return Stream.of(
				Arguments.of(new int[]{0}),
				Arguments.of(new int[]{42, 42, 42}),
				Arguments.of(new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE}),
				Arguments.of(new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE}),
				Arguments.of(new int[]{-1, -1, -1, -1, -1})
		);
	}

	static Stream<Arguments> i64ConstantArrays() {
		return Stream.of(
				Arguments.of(new long[]{0L}),
				Arguments.of(new long[]{100L, 100L, 100L}),
				Arguments.of(new long[]{Long.MIN_VALUE, Long.MIN_VALUE}),
				Arguments.of(new long[]{Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE})
		);
	}

	@ParameterizedTest
	@MethodSource("i32ConstantArrays")
	void encodeDecode_i32_isLossless(int[] data) {
		// Given
		var sut = new ConstantEncoding();
		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(sut);
		var le = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

		// When
		EncodeResult encoded = sut.encode(I32_DTYPE, data);
		DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, I32_DTYPE, registry);
		Array result = sut.decode(ctx);

		// Then
		assertThat(result.length()).isEqualTo(data.length);
		for (int i = 0; i < data.length; i++) {
			assertThat(result.buffer(0).get(le, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
		}
	}

	@ParameterizedTest
	@MethodSource("i64ConstantArrays")
	void encodeDecode_i64_isLossless(long[] data) {
		// Given
		var sut = new ConstantEncoding();
		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(sut);
		var le = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

		// When
		EncodeResult encoded = sut.encode(I64_DTYPE, data);
		DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, I64_DTYPE, registry);
		Array result = sut.decode(ctx);

		// Then
		assertThat(result.length()).isEqualTo(data.length);
		for (int i = 0; i < data.length; i++) {
			assertThat(result.buffer(0).get(le, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
		}
	}
}
