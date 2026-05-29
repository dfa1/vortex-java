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

/// Property: encode then decode is lossless; monotonic sequences compress smaller than raw.
class DeltaEncodingTest {

	private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);
	private static final DType I32_DTYPE = new DType.Primitive(PType.I32, false);

	static Stream<long[]> i64Arrays() {
		return Stream.of(
				new long[]{0},
				new long[]{Long.MIN_VALUE},
				new long[]{0, 1, 2, 3, 4, 5, 6, 7},
				new long[]{100, 200, 300, 400, 500},
				new long[]{-100, -50, 0, 50, 100},
				new long[]{1000, 999, 998, 997, 996}
		);
	}

	static Stream<int[]> i32Arrays() {
		return Stream.of(
				new int[]{0},
				new int[]{Integer.MIN_VALUE},
				new int[]{0, 1, 2, 3, 4, 5, 6, 7},
				new int[]{10, 20, 30, 40, 50},
				new int[]{-5, -4, -3, -2, -1, 0}
		);
	}

	static Stream<Arguments> monotoneI64Arrays() {
		return Stream.of(
				// strictly monotone with constant delta → very compressible
				Arguments.of("ascending-1", new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}),
				Arguments.of("ascending-100", new long[]{0, 100, 200, 300, 400, 500, 600, 700, 800, 900}),
				Arguments.of("descending", new long[]{1000, 999, 998, 997, 996, 995, 994, 993, 992, 991})
		);
	}

	@ParameterizedTest
	@MethodSource("i64Arrays")
	void encodeDecode_i64_isLossless(long[] data) {
		// Given
		var sut = new DeltaEncoding();
		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(sut);

		// When
		EncodeResult encoded = sut.encode(I64_DTYPE, data);
		DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, I64_DTYPE, registry);
		Array result = sut.decode(ctx);

		// Then
		assertThat(result.length()).isEqualTo(data.length);
		var le = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < data.length; i++) {
			assertThat(result.buffer(0).get(le, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
		}
	}

	@ParameterizedTest
	@MethodSource("i32Arrays")
	void encodeDecode_i32_isLossless(int[] data) {
		// Given
		var sut = new DeltaEncoding();
		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(sut);

		// When
		EncodeResult encoded = sut.encode(I32_DTYPE, data);
		DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, I32_DTYPE, registry);
		Array result = sut.decode(ctx);

		// Then
		assertThat(result.length()).isEqualTo(data.length);
		var le = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < data.length; i++) {
			assertThat(result.buffer(0).get(le, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("monotoneI64Arrays")
	void encodedSize_monotoneSequence_compressesWellVsRaw(String name, long[] data) {
		// Given
		var sut = new DeltaEncoding();

		// When
		EncodeResult encoded = sut.encode(I64_DTYPE, data);

		// Then — delta-encoded size < raw size (n * 8 bytes)
		int encodedBytes = encoded.buffers().stream().mapToInt(java.nio.Buffer::limit).sum();
		int rawBytes = data.length * 8;
		assertThat(encodedBytes).isLessThan(rawBytes);
	}
}
