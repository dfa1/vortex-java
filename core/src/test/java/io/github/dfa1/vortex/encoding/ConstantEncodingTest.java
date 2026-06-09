package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
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
class ConstantEncodingTest {

    @Nested
    class Decode {

        @Test
        void decode_largeRowCount_bufferStaysConstantSize() {
            // Given — 10M rows would allocate 80 MB if the decoder materializes every element;
            // the correct impl stores exactly one element regardless of logical length.
            long rowCount = 10_000_000L;
            var sut = new ConstantEncoding();
            Registry registry = TestRegistry.of(sut);

            // When
            EncodeResult encoded = sut.encode(DTypes.I64, new long[]{42L}, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I64, registry);
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(rowCount);
            // Constant encoding must not materialize the full array: the backing buffer must
            // hold exactly one element. Before fix: buffer is rowCount * 8 bytes.
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
            // Given
            var sut = new ConstantEncoding();
            Registry registry = TestRegistry.of(sut);
            var le = PTypeIO.LE_INT;

            // When
            EncodeResult encoded = sut.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I32, registry);
            Array result = sut.decode(ctx);

            // Then — buffer holds one element; logical length is n
            assertThat(result.length()).isEqualTo(data.length);
            assertThat(ArraySegments.of(result).byteSize()).isEqualTo(Integer.BYTES);
            assertThat(ArraySegments.of(result).get(le, 0L)).isEqualTo(data[0]);
        }

        @ParameterizedTest
        @MethodSource("i64ConstantArrays")
        void encodeDecode_i64_isLossless(long[] data) {
            // Given
            var sut = new ConstantEncoding();
            Registry registry = TestRegistry.of(sut);
            var le = PTypeIO.LE_LONG;

            // When
            EncodeResult encoded = sut.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I64, registry);
            Array result = sut.decode(ctx);

            // Then — buffer holds one element; logical length is n
            assertThat(result.length()).isEqualTo(data.length);
            assertThat(ArraySegments.of(result).byteSize()).isEqualTo(Long.BYTES);
            assertThat(ArraySegments.of(result).get(le, 0L)).isEqualTo(data[0]);
        }
    }

    /// ConstantEncoding stores 1 element in the buffer but reports length=N.
    /// Primitive Array accessors must broadcast that single element across every
    /// logical index, not OOB. Regression-guard for commit ed658b7 (added the
    /// broadcast semantic) and 051a794 (fast-path branch-split — preserve broadcast
    /// only on the slow path where `elementCount != length`).
    @Nested
    class Broadcast {

        @ParameterizedTest
        @ValueSource(longs = {1, 2, 10, 1_000, 131_072, 1_000_000L})
        void i64_getLong_returnsConstantAtEveryIndex(long rowCount) {
            // Given
            long constant = 0xDEADBEEFCAFEBABEL;
            var sut = new ConstantEncoding();
            Registry registry = TestRegistry.of(sut);
            EncodeResult encoded = sut.encode(DTypes.I64, new long[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I64, registry);

            // When
            LongArray result = (LongArray) sut.decode(ctx);

            // Then — getLong at first, last, and arbitrary midpoints all return the constant.
            // Catches: missing modulo (OOB or wrong value) and accidental skip of broadcast branch.
            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getLong(0)).isEqualTo(constant);
            assertThat(result.getLong(rowCount - 1)).isEqualTo(constant);
            if (rowCount >= 3) {
                assertThat(result.getLong(rowCount / 2)).isEqualTo(constant);
            }
        }

        @Test
        void i64_fold_broadcastsAcrossAllRows() {
            // Given — fold is the hot path for column aggregates. Must use the broadcast
            // branch when elementCount != length, otherwise fold returns wrong sum.
            long rowCount = 1_000_000L;
            long constant = 7L;
            var sut = new ConstantEncoding();
            Registry registry = TestRegistry.of(sut);
            EncodeResult encoded = sut.encode(DTypes.I64, new long[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I64, registry);

            // When
            LongArray result = (LongArray) sut.decode(ctx);
            long sum = result.fold(0L, Long::sum);

            // Then — every row contributes the constant; total is rowCount * constant.
            // Pre-fix (no modulo) the fold would OOB on row 1; post-bug-fix without branch-split
            // the result is correct but ~25% slower.
            assertThat(sum).isEqualTo(rowCount * constant);
        }

        @Test
        void i32_getInt_broadcastsAcrossEveryIndex() {
            // Given
            long rowCount = 10_000L;
            int constant = -123_456;
            var sut = new ConstantEncoding();
            Registry registry = TestRegistry.of(sut);
            EncodeResult encoded = sut.encode(DTypes.I32, new int[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I32, registry);

            // When
            IntArray result = (IntArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getInt(0)).isEqualTo(constant);
            assertThat(result.getInt(rowCount - 1)).isEqualTo(constant);
            assertThat(result.fold(0, Integer::sum)).isEqualTo((int) (rowCount * constant));
        }

        @Test
        void f64_getDouble_broadcastsAcrossEveryIndex() {
            // Given
            long rowCount = 10_000L;
            double constant = 3.141592653589793;
            var sut = new ConstantEncoding();
            Registry registry = TestRegistry.of(sut);
            EncodeResult encoded = sut.encode(DTypes.F64, new double[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.F64, registry);

            // When
            DoubleArray result = (DoubleArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getDouble(0)).isEqualTo(constant);
            assertThat(result.getDouble(rowCount - 1)).isEqualTo(constant);
            // Iterative double sum drifts (~1e-10 per 10K rows) — use tolerance, not strict equality.
            assertThat(result.fold(0.0, Double::sum))
                    .isCloseTo(rowCount * constant, org.assertj.core.data.Offset.offset(1e-6));
        }

        @Test
        void f32_getFloat_broadcastsAcrossEveryIndex() {
            // Given
            long rowCount = 10_000L;
            float constant = 2.71828f;
            var sut = new ConstantEncoding();
            Registry registry = TestRegistry.of(sut);
            EncodeResult encoded = sut.encode(DTypes.F32, new float[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.F32, registry);

            // When
            FloatArray result = (FloatArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getFloat(0)).isEqualTo(constant);
            assertThat(result.getFloat(rowCount - 1)).isEqualTo(constant);
        }

        @Test
        void i16_getShort_broadcastsAcrossEveryIndex() {
            // Given
            long rowCount = 10_000L;
            short constant = (short) -12345;
            var sut = new ConstantEncoding();
            Registry registry = TestRegistry.of(sut);
            EncodeResult encoded = sut.encode(DTypes.I16, new short[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I16, registry);

            // When
            ShortArray result = (ShortArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getShort(0)).isEqualTo(constant);
            assertThat(result.getShort(rowCount - 1)).isEqualTo(constant);
        }

        @Test
        void i8_getByte_broadcastsAcrossEveryIndex() {
            // Given
            long rowCount = 10_000L;
            byte constant = (byte) -42;
            var sut = new ConstantEncoding();
            Registry registry = TestRegistry.of(sut);
            EncodeResult encoded = sut.encode(DTypes.I8, new byte[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I8, registry);

            // When
            ByteArray result = (ByteArray) sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getByte(0)).isEqualTo(constant);
            assertThat(result.getByte(rowCount - 1)).isEqualTo(constant);
        }
    }
}
