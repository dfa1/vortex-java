package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SequenceEncodingTest {

	private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);
	private static final DType I32_DTYPE = new DType.Primitive(PType.I32, false);
	private static final DType F64_DTYPE = new DType.Primitive(PType.F64, false);
	private static final DType F32_DTYPE = new DType.Primitive(PType.F32, false);

	@Nested
	class Encode {

		@Test
		void encodingId_isVortexSequence() {
			// Given / When / Then
			assertThat(new SequenceEncoding().encodingId()).isEqualTo(EncodingId.VORTEX_SEQUENCE);
		}

		@Test
		void encode_i64_roundTrips() {
			// Given
			var sut = new SequenceEncoding();
			long[] data = {10L, 12L, 14L, 16L};

			// When
			EncodeResult result = sut.encode(I64_DTYPE, data, EncodeTestHelper.testCtx());
			DecodeContext ctx = encodeResultToCtx(result, I64_DTYPE, data.length);
			LongArray decoded = (LongArray) sut.decode(ctx);

			// Then
			for (int i = 0; i < data.length; i++) {
				assertThat(decoded.getLong(i)).as("index %d", i).isEqualTo(data[i]);
			}
		}

		@Test
		void encode_f64_roundTrips() {
			// Given
			var sut = new SequenceEncoding();
			double[] data = {1.0, 1.5, 2.0, 2.5};

			// When
			EncodeResult result = sut.encode(F64_DTYPE, data, EncodeTestHelper.testCtx());
			DecodeContext ctx = encodeResultToCtx(result, F64_DTYPE, data.length);
			DoubleArray decoded = (DoubleArray) sut.decode(ctx);

			// Then
			for (int i = 0; i < data.length; i++) {
				assertThat(decoded.getDouble(i)).as("index %d", i).isEqualTo(data[i]);
			}
		}

		@Test
		void encode_nonArithmeticSequence_throwsVortexException() {
			// Given
			var sut = new SequenceEncoding();
			long[] data = {1L, 2L, 4L};

			// When / Then
			assertThatThrownBy(() -> sut.encode(I64_DTYPE, data, EncodeTestHelper.testCtx()))
					.isInstanceOf(VortexException.class);
		}

		@Test
		void encode_nonPrimitiveDtype_throwsVortexException() {
			// Given
			var sut = new SequenceEncoding();

			// When / Then
			assertThatThrownBy(() -> sut.encode(new DType.Utf8(false), new long[]{1L}, EncodeTestHelper.testCtx()))
					.isInstanceOf(VortexException.class);
		}

		private static DecodeContext encodeResultToCtx(EncodeResult result, DType dtype, long n) {
			ByteBuffer meta = result.rootNode().metadata();
			ArrayNode node = ArrayNode.of(EncodingId.VORTEX_SEQUENCE, meta, new ArrayNode[0], new int[0], null);
			return new DecodeContext(node, dtype, n, new MemorySegment[0], EncodingRegistry.empty(), Arena.ofAuto());
		}
	}

	@Nested
	class Decode {

		@ParameterizedTest
		@MethodSource("i64Sequences")
		void decode_i64_generatesCorrectSequence(long base, long mul, long[] expected) {
			// Given
			var sut = new SequenceEncoding();
			DecodeContext ctx = makeCtx(intMeta(base, mul), I64_DTYPE, expected.length);

			// When
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(expected.length);
			for (int i = 0; i < expected.length; i++) {
				LongArray longArray = (LongArray) result;
				assertThat(longArray.getLong(i)).as("index %d", i).isEqualTo(expected[i]);
			}
		}

		@ParameterizedTest
		@MethodSource("i32Sequences")
		void decode_i32_generatesCorrectSequence(long base, long mul, int[] expected) {
			// Given
			var sut = new SequenceEncoding();
			DecodeContext ctx = makeCtx(intMeta(base, mul), I32_DTYPE, expected.length);

			// When
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(expected.length);
			for (int i = 0; i < expected.length; i++) {
				IntArray longArray = (IntArray) result;
				assertThat(longArray.getInt(i)).as("index %d", i).isEqualTo(expected[i]);
			}
		}

		@Test
		void decode_f64_generatesCorrectSequence() {
			// Given
			var sut = new SequenceEncoding();
			DecodeContext ctx = makeCtx(f64Meta(1.0, 0.5), F64_DTYPE, 4);

			// When
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(4);
			DoubleArray doubleArray = (DoubleArray) result;
			assertThat(doubleArray.getDouble(0)).isEqualTo(1.0);
			assertThat(doubleArray.getDouble(1)).isEqualTo(1.5);
			assertThat(doubleArray.getDouble(2)).isEqualTo(2.0);
			assertThat(doubleArray.getDouble(3)).isEqualTo(2.5);
		}

		@Test
		void decode_f32_generatesCorrectSequence() {
			// Given
			var sut = new SequenceEncoding();
			DecodeContext ctx = makeCtx(f32Meta(0.0f, 1.0f), F32_DTYPE, 3);

			// When
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(3);
			FloatArray floatArray = (FloatArray) result;
			assertThat(floatArray.getFloat(0)).isEqualTo(0.0f);
			assertThat(floatArray.getFloat(1)).isEqualTo(1.0f);
			assertThat(floatArray.getFloat(2)).isEqualTo(2.0f);
		}

		@Test
		void decode_emptySequence_returnsZeroLengthArray() {
			// Given
			var sut = new SequenceEncoding();
			DecodeContext ctx = makeCtx(intMeta(0, 1), I64_DTYPE, 0);

			// When
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isZero();
		}

		@Test
		void decode_missingMetadata_throwsVortexException() {
			// Given
			var sut = new SequenceEncoding();
			ArrayNode node = ArrayNode.of(EncodingId.VORTEX_SEQUENCE, null, new ArrayNode[0], new int[0], null);
			DecodeContext ctx = new DecodeContext(node, I64_DTYPE, 3, new MemorySegment[0], EncodingRegistry.empty(), Arena.ofAuto());

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class);
		}

		@Test
		void decode_f16_throwsVortexException() {
			// Given
			var sut = new SequenceEncoding();
			DType f16 = new DType.Primitive(PType.F16, false);
			DecodeContext ctx = makeCtx(intMeta(0, 1), f16, 3);

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class);
		}

		@Test
		void decode_nonPrimitiveDtype_throwsVortexException() {
			// Given
			var sut = new SequenceEncoding();
			DType utf8 = new DType.Utf8(false);
			DecodeContext ctx = makeCtx(intMeta(0, 1), utf8, 3);

			// When / Then
			assertThatThrownBy(() -> sut.decode(ctx))
					.isInstanceOf(VortexException.class);
		}

		static Stream<Arguments> i64Sequences() {
			return Stream.of(
					Arguments.of(0L, 1L, new long[]{0, 1, 2, 3, 4}),
					Arguments.of(10L, 2L, new long[]{10, 12, 14, 16}),
					Arguments.of(-5L, -1L, new long[]{-5, -6, -7}),
					Arguments.of(100L, 0L, new long[]{100, 100, 100}),
					Arguments.of(Long.MAX_VALUE, 0L, new long[]{Long.MAX_VALUE})
			);
		}

		static Stream<Arguments> i32Sequences() {
			return Stream.of(
					Arguments.of(0L, 1L, new int[]{0, 1, 2, 3}),
					Arguments.of(100L, -10L, new int[]{100, 90, 80}),
					Arguments.of((long) Integer.MAX_VALUE, 0L, new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE})
			);
		}

		private static DecodeContext makeCtx(byte[] meta, DType dtype, long n) {
			ArrayNode node = ArrayNode.of(
					EncodingId.VORTEX_SEQUENCE,
					ByteBuffer.wrap(meta),
					new ArrayNode[0], new int[0], null);
			return new DecodeContext(node, dtype, n, new MemorySegment[0], EncodingRegistry.empty(), Arena.ofAuto());
		}

		private static byte[] intMeta(long base, long mul) {
			return EncodingProtos.SequenceMetadata.newBuilder()
					.setBase(ScalarProtos.ScalarValue.newBuilder().setInt64Value(base))
					.setMultiplier(ScalarProtos.ScalarValue.newBuilder().setInt64Value(mul))
					.build().toByteArray();
		}

		private static byte[] f64Meta(double base, double mul) {
			return EncodingProtos.SequenceMetadata.newBuilder()
					.setBase(ScalarProtos.ScalarValue.newBuilder().setF64Value(base))
					.setMultiplier(ScalarProtos.ScalarValue.newBuilder().setF64Value(mul))
					.build().toByteArray();
		}

		private static byte[] f32Meta(float base, float mul) {
			return EncodingProtos.SequenceMetadata.newBuilder()
					.setBase(ScalarProtos.ScalarValue.newBuilder().setF32Value(base))
					.setMultiplier(ScalarProtos.ScalarValue.newBuilder().setF32Value(mul))
					.build().toByteArray();
		}
	}
}
