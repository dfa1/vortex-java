package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.FrameOfReferenceEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
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

class FrameOfReferenceEncodingEncoderTest {

    private static final FrameOfReferenceEncodingEncoder ENCODER = new FrameOfReferenceEncodingEncoder();
    private static final FrameOfReferenceEncodingDecoder DECODER = new FrameOfReferenceEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    @Nested
    class Decode {

        private static DecodeContext buildForContext(
                DType dtype, long reference, long[] residuals, PType ptype
        ) {
            byte[] metaBytes = ProtoScalarValue.ofInt64Value(reference).encode();

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
                    EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
            ArrayNode forNode = ArrayNode.of(
                    EncodingId.FASTLANES_FOR, MemorySegment.ofArray(metaBytes),
                    new ArrayNode[]{childNode}, new int[0]);

            MemorySegment[] segments = {MemorySegment.ofArray(childBytes)};
            return new DecodeContext(forNode, dtype, residuals.length, segments, REGISTRY, java.lang.foreign.Arena.global());
        }

        @Test
        void decode_i64_addsReferenceToResiduals() {
            // Given
            long reference = 1000L;
            long[] residuals = {0, 1, 2, 3, 4};
            long[] expected = {1000, 1001, 1002, 1003, 1004};
            DecodeContext ctx = buildForContext(DTypes.I64, reference, residuals, PType.I64);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(residuals.length);
            LongArray arr = (LongArray) result;
            for (int i = 0; i < expected.length; i++) {
                assertThat(arr.getLong(i)).as("index %d", i).isEqualTo(expected[i]);
            }
        }

        @Test
        void decode_i32_addsReferenceToResiduals() {
            // Given
            long reference = -100L;
            long[] residuals = {0, 5, 10, 15};
            int[] expected = {-100, -95, -90, -85};
            DecodeContext ctx = buildForContext(DTypes.I32, reference, residuals, PType.I32);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(residuals.length);
            IntArray arr = (IntArray) result;
            for (int i = 0; i < expected.length; i++) {
                assertThat(arr.getInt(i)).as("index %d", i).isEqualTo(expected[i]);
            }
        }

        @Test
        void decode_zeroReference_returnsChildUnchanged() {
            // Given
            long[] residuals = {7, 8, 9};
            DecodeContext ctx = buildForContext(DTypes.I64, 0L, residuals, PType.I64);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            LongArray arr = (LongArray) result;
            for (int i = 0; i < residuals.length; i++) {
                assertThat(arr.getLong(i)).isEqualTo(residuals[i]);
            }
        }

        @ParameterizedTest
        @ValueSource(longs = {Long.MIN_VALUE, Long.MAX_VALUE, -1L, 1L})
        void decode_wrappingAdd_i64(long reference) {
            // Given
            long[] residuals = {1L};
            DecodeContext ctx = buildForContext(DTypes.I64, reference, residuals, PType.I64);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(((LongArray) result).getLong(0)).isEqualTo(residuals[0] + reference);
        }

        @Test
        void decode_nullableResiduals_returnsMaskedArrayWithCorrectValues() {
            // Given
            long reference = 100L;
            long[] residuals = {0, 0, 5, 0};
            MemorySegment validitySeg = MemorySegment.ofArray(new byte[]{0x05});

            byte[] residualBytes = new byte[residuals.length * 4];
            ByteBuffer bb = ByteBuffer.wrap(residualBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (long v : residuals) {
                bb.putInt((int) v);
            }

            ArrayNode validityNode = ArrayNode.of(
                    EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1});
            ArrayNode primNode = ArrayNode.of(
                    EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[]{validityNode}, new int[]{0});
            byte[] metaBytes = ProtoScalarValue.ofInt64Value(reference).encode();
            ArrayNode forNode = ArrayNode.of(
                    EncodingId.FASTLANES_FOR, MemorySegment.ofArray(metaBytes), new ArrayNode[]{primNode}, new int[0]);

            ReadRegistry registry = TestRegistry.ofDecoders(
                    new FrameOfReferenceEncodingDecoder(), new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

            MemorySegment[] segments = {MemorySegment.ofArray(residualBytes), validitySeg};
            DecodeContext ctx = new DecodeContext(
                    forNode, DTypes.I32, residuals.length, segments, registry, java.lang.foreign.Arena.global());

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result).isInstanceOf(MaskedArray.class);
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.isValid(0)).isTrue();
            assertThat(masked.isValid(1)).isFalse();
            assertThat(masked.isValid(2)).isTrue();
            assertThat(masked.isValid(3)).isFalse();
            assertThat(((IntArray) masked.inner()).getInt(0)).isEqualTo(100);
            assertThat(((IntArray) masked.inner()).getInt(2)).isEqualTo(105);
        }
    }

    @Nested
    class Encode {

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

        @ParameterizedTest
        @MethodSource("i64Arrays")
        void encodeDecode_i64_isLossless(long[] data) {
            // Given
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, data.length, DTypes.I64, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            LongArray arr = (LongArray) result;
            for (int i = 0; i < data.length; i++) {
                assertThat(arr.getLong(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @MethodSource("i32Arrays")
        void encodeDecode_i32_isLossless(int[] data) {
            // Given
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, data.length, DTypes.I32, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            IntArray arr = (IntArray) result;
            for (int i = 0; i < data.length; i++) {
                assertThat(arr.getInt(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }
    }
}
