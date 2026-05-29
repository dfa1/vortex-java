package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: encode then decode is lossless for all primitive types and array sizes.
class PrimitiveEncodingTest {

	static Stream<long[]> longArrays() {
		return Stream.of(
				new long[]{},
				new long[]{0},
				new long[]{Long.MIN_VALUE, Long.MAX_VALUE},
				new long[]{-1, 0, 1, 2, 3, 4, 5},
				new long[]{1, 1000, -1000, 42, Long.MAX_VALUE / 2}
		);
	}

	static Stream<int[]> intArrays() {
		return Stream.of(
				new int[]{},
				new int[]{0},
				new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE},
				new int[]{-1, 0, 1, 100, -100},
				new int[]{7, 14, 21, 42, 99, -7}
		);
	}

	static Stream<double[]> doubleArrays() {
		return Stream.of(
				new double[]{},
				new double[]{0.0},
				new double[]{Double.MIN_VALUE, Double.MAX_VALUE},
				new double[]{-1.5, 0.0, 1.5, 3.14159, -2.71828},
				new double[]{1e10, -1e10, 1e-10}
		);
	}

	@ParameterizedTest
	@MethodSource("longArrays")
	void encodeDecode_i64_isLossless(long[] data) {
		// Given
		DType dtype = new DType.Primitive(PType.I64, false);
		var sut = new PrimitiveEncoding();
		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(sut);

		// When
		EncodeResult encoded = sut.encode(dtype, data);
		DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, dtype, registry);
		Array result = sut.decode(ctx);

		// Then — roundtrip lossless
		assertThat(result.length()).isEqualTo(data.length);
		var le = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < data.length; i++) {
			assertThat(result.buffer(0).get(le, (long) i * 8)).isEqualTo(data[i]);
		}
	}

	@ParameterizedTest
	@MethodSource("intArrays")
	void encodeDecode_i32_isLossless(int[] data) {
		// Given
		DType dtype = new DType.Primitive(PType.I32, false);
		var sut = new PrimitiveEncoding();
		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(sut);

		// When
		EncodeResult encoded = sut.encode(dtype, data);
		DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, dtype, registry);
		Array result = sut.decode(ctx);

		// Then — roundtrip lossless
		assertThat(result.length()).isEqualTo(data.length);
		var le = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < data.length; i++) {
			assertThat(result.buffer(0).get(le, (long) i * 4)).isEqualTo(data[i]);
		}
	}

	@ParameterizedTest
	@MethodSource("doubleArrays")
	void encodeDecode_f64_isLossless(double[] data) {
		// Given
		DType dtype = new DType.Primitive(PType.F64, false);
		var sut = new PrimitiveEncoding();
		EncodingRegistry registry = EncodingRegistry.empty();
		registry.register(sut);

		// When
		EncodeResult encoded = sut.encode(dtype, data);
		DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, dtype, registry);
		Array result = sut.decode(ctx);

		// Then — roundtrip lossless
		assertThat(result.length()).isEqualTo(data.length);
		var le = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < data.length; i++) {
			assertThat(result.buffer(0).get(le, (long) i * 8)).isEqualTo(data[i]);
		}
	}

	@ParameterizedTest
	@MethodSource("longArrays")
	void encodedSize_equalsBytesInBuffer(long[] data) {
		// Given
		DType dtype = new DType.Primitive(PType.I64, false);
		var sut = new PrimitiveEncoding();

		// When
		EncodeResult encoded = sut.encode(dtype, data);

		// Then — no compression: wire size = n * elemBytes
		long totalBytes = encoded.buffers().stream().mapToLong(java.lang.foreign.MemorySegment::byteSize).sum();
		assertThat(totalBytes).isEqualTo((long) data.length * 8);
	}
}
