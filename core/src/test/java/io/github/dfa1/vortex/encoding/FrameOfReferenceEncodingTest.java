package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.MaskedArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FrameOfReferenceEncodingTest {


	@Nested
	class Decode {

		@Test
		void decode_i64_addsReferenceToResiduals() {
			// Given
			long reference = 1000L;
			long[] residuals = {0, 1, 2, 3, 4};
			long[] expected = {1000, 1001, 1002, 1003, 1004};

			DecodeContext ctx = buildForContext(DTypes.I64, reference, residuals, PType.I64);
			FrameOfReferenceEncoding sut = new FrameOfReferenceEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(residuals.length);
			var layout = PTypeIO.LE_LONG;
			for (int i = 0; i < expected.length; i++) {
				assertThat(result.buffer(0).get(layout, (long) i * 8))
						.as("index %d", i)
						.isEqualTo(expected[i]);
			}
		}

		@Test
		void decode_i32_addsReferenceToResiduals() {
			// Given
			long reference = -100L;
			long[] residuals = {0, 5, 10, 15};
			int[] expected = {-100, -95, -90, -85};

			DecodeContext ctx = buildForContext(DTypes.I32, reference, residuals, PType.I32);
			FrameOfReferenceEncoding sut = new FrameOfReferenceEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(residuals.length);
			var layout = PTypeIO.LE_INT;
			for (int i = 0; i < expected.length; i++) {
				assertThat(result.buffer(0).get(layout, (long) i * 4))
						.as("index %d", i)
						.isEqualTo(expected[i]);
			}
		}

		@Test
		void decode_zeroReference_returnsChildUnchanged() {
			// Given — reference == 0, should skip the add entirely
			long[] residuals = {7, 8, 9};
			DecodeContext ctx = buildForContext(DTypes.I64, 0L, residuals, PType.I64);
			FrameOfReferenceEncoding sut = new FrameOfReferenceEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then — values unchanged
			var layout = PTypeIO.LE_LONG;
			for (int i = 0; i < residuals.length; i++) {
				assertThat(result.buffer(0).get(layout, (long) i * 8)).isEqualTo(residuals[i]);
			}
		}

		@ParameterizedTest
		@ValueSource(longs = {Long.MIN_VALUE, Long.MAX_VALUE, -1L, 1L})
		void decode_wrappingAdd_i64(long reference) {
			// Given — wrapping arithmetic: MAX + 1 wraps to MIN
			long[] residuals = {1L};
			DecodeContext ctx = buildForContext(DTypes.I64, reference, residuals, PType.I64);
			FrameOfReferenceEncoding sut = new FrameOfReferenceEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then
			var layout = PTypeIO.LE_LONG;
			long got = result.buffer(0).get(layout, 0L);
			assertThat(got).isEqualTo(residuals[0] + reference);
		}

		@Test
		void decode_nullableResiduals_returnsMaskedArrayWithCorrectValues() {
			// Given — 4 I32 residuals; positions 1 and 3 are null (validity: 0b00000101 = 0x05)
			// Residuals: [0, 0, 5, 0], reference: 100 → valid outputs: [100, ?, 105, ?]
			long reference = 100L;
			long[] residuals = {0, 0, 5, 0};
			MemorySegment validitySeg = MemorySegment.ofArray(new byte[]{0x05}); // bits 0,2

			byte[] residualBytes = new byte[residuals.length * 4];
			ByteBuffer bb = ByteBuffer.wrap(residualBytes).order(ByteOrder.LITTLE_ENDIAN);
			for (long v : residuals) {
				bb.putInt((int) v);
			}

			ArrayNode validityNode = ArrayNode.of(
					EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1}, ArrayStats.empty());
			ArrayNode primNode = ArrayNode.of(
					EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[]{validityNode}, new int[]{0}, ArrayStats.empty());
			byte[] metaBytes = ScalarProtos.ScalarValue.newBuilder().setInt64Value(reference).build().toByteArray();
			ArrayNode forNode = ArrayNode.of(
					EncodingId.FASTLANES_FOR, ByteBuffer.wrap(metaBytes), new ArrayNode[]{primNode}, new int[0], ArrayStats.empty());

			EncodingRegistry registry = TestRegistry.of(new FrameOfReferenceEncoding(), new PrimitiveEncoding(), new BoolEncoding());

			MemorySegment[] segments = {MemorySegment.ofArray(residualBytes), validitySeg};
			DecodeContext ctx = new DecodeContext(
					forNode, DTypes.I32, residuals.length, segments, registry, java.lang.foreign.Arena.global());
			FrameOfReferenceEncoding sut = new FrameOfReferenceEncoding();

			// When
			Array result = sut.decode(ctx);

			// Then — MaskedArray; reference added to valid positions only
			assertThat(result).isInstanceOf(MaskedArray.class);
			MaskedArray masked = (MaskedArray) result;
			assertThat(masked.isValid(0)).isTrue();
			assertThat(masked.isValid(1)).isFalse();
			assertThat(masked.isValid(2)).isTrue();
			assertThat(masked.isValid(3)).isFalse();
			var layout = PTypeIO.LE_INT;
			assertThat(masked.child(0).buffer(0).get(layout, 0L)).isEqualTo(100);
			assertThat(masked.child(0).buffer(0).get(layout, 8L)).isEqualTo(105);
		}

		private static DecodeContext buildForContext(
				DType dtype, long reference, long[] residuals, PType ptype
		) {
			byte[] metaBytes = ScalarProtos.ScalarValue.newBuilder()
					.setInt64Value(reference)
					.build()
					.toByteArray();

			int elemBytes = ptype.byteSize();
			byte[] childBytes = new byte[residuals.length * elemBytes];
			ByteBuffer bb = ByteBuffer.wrap(childBytes).order(ByteOrder.LITTLE_ENDIAN);
			for (long v : residuals) {
				switch (ptype) {
					case I32, U32 -> bb.putInt((int) v);
					case I64, U64 -> bb.putLong(v);
					default -> throw new UnsupportedOperationException(ptype.name());
				}
			}

			ArrayNode childNode = ArrayNode.of(
					EncodingId.VORTEX_PRIMITIVE,
					null,
					new ArrayNode[0],
					new int[]{0},
					ArrayStats.empty()
			);

			ArrayNode forNode = ArrayNode.of(
					EncodingId.FASTLANES_FOR,
					ByteBuffer.wrap(metaBytes),
					new ArrayNode[]{childNode},
					new int[0],
					ArrayStats.empty()
			);

			MemorySegment[] segments = {MemorySegment.ofArray(childBytes)};

			EncodingRegistry registry = TestRegistry.of(new FrameOfReferenceEncoding(), new PrimitiveEncoding());

			return new DecodeContext(forNode, dtype, residuals.length, segments, registry, java.lang.foreign.Arena.global());
		}
	}

	@Nested
	class Encode {

		@ParameterizedTest
		@MethodSource("i64Arrays")
		void encodeDecode_i64_isLossless(long[] data) {
			// Given
			var sut = new FrameOfReferenceEncoding();
			EncodingRegistry registry = TestRegistry.withPrimitive(sut);
			var le = PTypeIO.LE_LONG;

			// When
			EncodeResult encoded = sut.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I64, registry);
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(data.length);
			for (int i = 0; i < data.length; i++) {
				assertThat(result.buffer(0).get(le, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
			}
		}

		@ParameterizedTest
		@MethodSource("i32Arrays")
		void encodeDecode_i32_isLossless(int[] data) {
			// Given
			var sut = new FrameOfReferenceEncoding();
			EncodingRegistry registry = TestRegistry.withPrimitive(sut);
			var le = PTypeIO.LE_INT;

			// When
			EncodeResult encoded = sut.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
			DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I32, registry);
			Array result = sut.decode(ctx);

			// Then
			assertThat(result.length()).isEqualTo(data.length);
			for (int i = 0; i < data.length; i++) {
				assertThat(result.buffer(0).get(le, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
			}
		}

		static Stream<long[]> i64Arrays() {
			return Stream.of(
					new long[]{0L},
					new long[]{1000L, 1001L, 1002L, 1003L},
					new long[]{-500L, -499L, -498L},
					new long[]{Long.MIN_VALUE, Long.MIN_VALUE + 1L, Long.MIN_VALUE + 2L},
					new long[]{42L, 42L, 42L}
			);
		}

		static Stream<int[]> i32Arrays() {
			return Stream.of(
					new int[]{0},
					new int[]{100, 101, 102, 103},
					new int[]{-10, -9, -8, -7},
					new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE + 1}
			);
		}
	}
}
