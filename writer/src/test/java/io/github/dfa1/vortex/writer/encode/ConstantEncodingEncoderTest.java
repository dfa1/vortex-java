package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.encoding.EncodeResult;
import io.github.dfa1.vortex.encoding.EncodeTestHelper;
import io.github.dfa1.vortex.reader.decode.DecodeTestHelper;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.reader.decode.ConstantEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: encode then decode is lossless for constant (all-equal) arrays.
/// Property: decode allocates O(1) memory regardless of rowCount.
class ConstantEncodingEncoderTest {

    private static final ConstantEncodingEncoder ENCODER = new ConstantEncodingEncoder();
    private static final ConstantEncodingDecoder DECODER = new ConstantEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER);

    @Nested
    class Decode {

        @Test
        void decode_largeRowCount_bufferStaysConstantSize() {
            // Given — 10M rows would allocate 80 MB if the decoder materializes every element;
            // the correct impl stores exactly one element regardless of logical length.
            long rowCount = 10_000_000L;

            // When
            EncodeResult encoded = ENCODER.encode(DTypes.I64, new long[]{42L}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I64, REGISTRY);
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(ArraySegments.of(result).byteSize())
                    .as("constant encoding must not allocate O(rowCount) memory")
                    .isEqualTo(Long.BYTES);
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, 0L)).isEqualTo(42L);
        }
    }

    @Nested
    class Encode {

        static Stream<Arguments> i32ConstantArrays() {
            return Stream.of(
                    Arguments.of((Object) new int[]{0}),
                    Arguments.of((Object) new int[]{42, 42, 42}),
                    Arguments.of((Object) new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE}),
                    Arguments.of((Object) new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE}),
                    Arguments.of((Object) new int[]{-1, -1, -1, -1, -1})
            );
        }

        static Stream<Arguments> i64ConstantArrays() {
            return Stream.of(
                    Arguments.of((Object) new long[]{0L}),
                    Arguments.of((Object) new long[]{100L, 100L, 100L}),
                    Arguments.of((Object) new long[]{Long.MIN_VALUE, Long.MIN_VALUE}),
                    Arguments.of((Object) new long[]{Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE})
            );
        }

        @ParameterizedTest
        @MethodSource("i32ConstantArrays")
        void encodeDecode_i32_isLossless(int[] data) {
            EncodeResult encoded = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I32, REGISTRY);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(data.length);
            assertThat(ArraySegments.of(result).byteSize()).isEqualTo(Integer.BYTES);
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, 0L)).isEqualTo(data[0]);
        }

        @ParameterizedTest
        @MethodSource("i64ConstantArrays")
        void encodeDecode_i64_isLossless(long[] data) {
            EncodeResult encoded = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I64, REGISTRY);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(data.length);
            assertThat(ArraySegments.of(result).byteSize()).isEqualTo(Long.BYTES);
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, 0L)).isEqualTo(data[0]);
        }
    }

    /// ConstantEncoding stores 1 element in the buffer but reports length=N.
    /// Primitive Array accessors must broadcast that single element across every
    /// logical index, not OOB.
    @Nested
    class Broadcast {

        @ParameterizedTest
        @ValueSource(longs = {1, 2, 10, 1_000, 131_072, 1_000_000L})
        void i64_getLong_returnsConstantAtEveryIndex(long rowCount) {
            long constant = 0xDEADBEEFCAFEBABEL;
            EncodeResult encoded = ENCODER.encode(DTypes.I64, new long[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I64, REGISTRY);

            LongArray result = (LongArray) DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getLong(0)).isEqualTo(constant);
            assertThat(result.getLong(rowCount - 1)).isEqualTo(constant);
            if (rowCount >= 3) {
                assertThat(result.getLong(rowCount / 2)).isEqualTo(constant);
            }
        }

        @Test
        void i64_fold_broadcastsAcrossAllRows() {
            long rowCount = 1_000_000L;
            long constant = 7L;
            EncodeResult encoded = ENCODER.encode(DTypes.I64, new long[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I64, REGISTRY);

            LongArray result = (LongArray) DECODER.decode(ctx);
            long sum = result.fold(0L, Long::sum);

            assertThat(sum).isEqualTo(rowCount * constant);
        }

        @Test
        void i32_getInt_broadcastsAcrossEveryIndex() {
            long rowCount = 10_000L;
            int constant = -123_456;
            EncodeResult encoded = ENCODER.encode(DTypes.I32, new int[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I32, REGISTRY);

            IntArray result = (IntArray) DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getInt(0)).isEqualTo(constant);
            assertThat(result.getInt(rowCount - 1)).isEqualTo(constant);
            assertThat(result.fold(0, Integer::sum)).isEqualTo((int) (rowCount * constant));
        }

        @Test
        void f64_getDouble_broadcastsAcrossEveryIndex() {
            long rowCount = 10_000L;
            double constant = 3.141592653589793;
            EncodeResult encoded = ENCODER.encode(DTypes.F64, new double[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.F64, REGISTRY);

            DoubleArray result = (DoubleArray) DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getDouble(0)).isEqualTo(constant);
            assertThat(result.getDouble(rowCount - 1)).isEqualTo(constant);
            assertThat(result.fold(0.0, Double::sum))
                    .isCloseTo(rowCount * constant, org.assertj.core.data.Offset.offset(1e-6));
        }

        @Test
        void f32_getFloat_broadcastsAcrossEveryIndex() {
            long rowCount = 10_000L;
            float constant = 2.71828f;
            EncodeResult encoded = ENCODER.encode(DTypes.F32, new float[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.F32, REGISTRY);

            FloatArray result = (FloatArray) DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getFloat(0)).isEqualTo(constant);
            assertThat(result.getFloat(rowCount - 1)).isEqualTo(constant);
        }

        @Test
        void i16_getShort_broadcastsAcrossEveryIndex() {
            long rowCount = 10_000L;
            short constant = (short) -12345;
            EncodeResult encoded = ENCODER.encode(DTypes.I16, new short[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I16, REGISTRY);

            ShortArray result = (ShortArray) DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getShort(0)).isEqualTo(constant);
            assertThat(result.getShort(rowCount - 1)).isEqualTo(constant);
        }

        @Test
        void i8_getByte_broadcastsAcrossEveryIndex() {
            long rowCount = 10_000L;
            byte constant = (byte) -42;
            EncodeResult encoded = ENCODER.encode(DTypes.I8, new byte[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I8, REGISTRY);

            ByteArray result = (ByteArray) DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getByte(0)).isEqualTo(constant);
            assertThat(result.getByte(rowCount - 1)).isEqualTo(constant);
        }
    }
}
