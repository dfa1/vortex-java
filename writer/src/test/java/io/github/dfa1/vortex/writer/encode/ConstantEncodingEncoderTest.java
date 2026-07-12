package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.proto.ProtoNullValue;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyConstantBoolArray;
import io.github.dfa1.vortex.reader.array.LazyConstantIntArray;
import io.github.dfa1.vortex.reader.array.LazyConstantLongArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.reader.decode.ConstantEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.MemorySegment;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Property: encode then decode is lossless for constant (all-equal) arrays.
/// Property: decode emits a metadata-only `LazyConstantXxxArray` — no buffer at any rowCount.
class ConstantEncodingEncoderTest {

    private static final ConstantEncodingEncoder ENCODER = new ConstantEncodingEncoder();
    private static final ConstantEncodingDecoder DECODER = new ConstantEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER);

    @Nested
    class Decode {

        @Test
        void decode_largeRowCount_emitsMetadataOnlyArray() {
            // Given — 10M rows would allocate 80 MB if the decoder materializes every element;
            // the correct impl emits a metadata-only LazyConstantLongArray with no buffer at all.
            long rowCount = 10_000_000L;

            // When
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I64, new long[]{42L}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, rowCount, DTypes.I64, REGISTRY);
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result)
                    .as("constant encoding must produce a metadata-only LazyConstantLongArray")
                    .isInstanceOf(LazyConstantLongArray.class);
            assertThat(((LazyConstantLongArray) result).value()).isEqualTo(42L);
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
            // Given / When
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, data.length, DTypes.I32, REGISTRY);
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            assertThat(result).isInstanceOf(LazyConstantIntArray.class);
            assertThat(((LazyConstantIntArray) result).value()).isEqualTo(data[0]);
        }

        @ParameterizedTest
        @MethodSource("i64ConstantArrays")
        void encodeDecode_i64_isLossless(long[] data) {
            // Given / When
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, data.length, DTypes.I64, REGISTRY);
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            assertThat(result).isInstanceOf(LazyConstantLongArray.class);
            assertThat(((LazyConstantLongArray) result).value()).isEqualTo(data[0]);
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
            // Given
            long constant = 0xDEADBEEFCAFEBABEL;
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I64, new long[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, rowCount, DTypes.I64, REGISTRY);

            // When
            LongArray result = (LongArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getLong(0)).isEqualTo(constant);
            assertThat(result.getLong(rowCount - 1)).isEqualTo(constant);
            if (rowCount >= 3) {
                assertThat(result.getLong(rowCount / 2)).isEqualTo(constant);
            }
        }

        @Test
        void i64_fold_broadcastsAcrossAllRows() {
            // Given
            long rowCount = 1_000_000L;
            long constant = 7L;
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I64, new long[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, rowCount, DTypes.I64, REGISTRY);

            // When
            LongArray result = (LongArray) DECODER.decode(ctx);
            long sum = result.fold(0L, Long::sum);

            // Then
            assertThat(sum).isEqualTo(rowCount * constant);
        }

        @Test
        void i32_getInt_broadcastsAcrossEveryIndex() {
            // Given
            long rowCount = 10_000L;
            int constant = -123_456;
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I32, new int[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, rowCount, DTypes.I32, REGISTRY);

            // When
            IntArray result = (IntArray) DECODER.decode(ctx);

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
            EncodeResult resultEncoded = ENCODER.encode(DTypes.F64, new double[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, rowCount, DTypes.F64, REGISTRY);

            // When
            DoubleArray result = (DoubleArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getDouble(0)).isEqualTo(constant);
            assertThat(result.getDouble(rowCount - 1)).isEqualTo(constant);
            assertThat(result.fold(0.0, Double::sum))
                    .isCloseTo(rowCount * constant, org.assertj.core.data.Offset.offset(1e-6));
        }

        @Test
        void f32_getFloat_broadcastsAcrossEveryIndex() {
            // Given
            long rowCount = 10_000L;
            float constant = 2.71828f;
            EncodeResult resultEncoded = ENCODER.encode(DTypes.F32, new float[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, rowCount, DTypes.F32, REGISTRY);

            // When
            FloatArray result = (FloatArray) DECODER.decode(ctx);

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
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I16, new short[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, rowCount, DTypes.I16, REGISTRY);

            // When
            ShortArray result = (ShortArray) DECODER.decode(ctx);

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
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I8, new byte[]{constant}, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, rowCount, DTypes.I8, REGISTRY);

            // When
            ByteArray result = (ByteArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(rowCount);
            assertThat(result.getByte(0)).isEqualTo(constant);
            assertThat(result.getByte(rowCount - 1)).isEqualTo(constant);
        }
    }

    @Nested
    class Bool {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void encodeDecode_isLossless(boolean value) {
            // Given / When
            boolean[] data = {value, value, value};
            EncodeResult resultEncoded = ENCODER.encode(DType.BOOL, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, data.length, DType.BOOL, REGISTRY);
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            assertThat(result).isInstanceOf(LazyConstantBoolArray.class);
            assertThat(((LazyConstantBoolArray) result).value()).isEqualTo(value);
        }

        @Test
        void encode_mixedValues_throws() {
            // Given
            boolean[] data = {true, false};

            // When
            // Then
            assertThatThrownBy(() -> ENCODER.encode(DType.BOOL, data, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("not a constant array");
        }

        @Test
        void encodeCascade_mixedValues_notApplicable() {
            // Given — the cascade contract needs a non-applicable step, not an exception, so a
            // sample-selected winner that isn't constant on the full data can be excluded and retried
            boolean[] data = {true, false};

            // When
            CascadeStep step = ENCODER.encodeCascade(DType.BOOL, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(step.applicable()).isFalse();
        }
    }

    /// Rust can write a constant array whose scalar is null (proto null_value tag).
    /// The decoder must return a [NullArray] — not 0 / false (#246).
    @Nested
    class NullScalar {

        static Stream<DType> nullableDtypes() {
            return Stream.of(DTypes.I64_N, DTypes.I32_N, DTypes.F64_N, DTypes.BOOL_N);
        }

        @ParameterizedTest
        @MethodSource("nullableDtypes")
        void decode_nullScalar_returnsNullArray(DType dtype) {
            // Given — scalar proto with only null_value tag set (Rust-written null constant)
            ProtoScalarValue nullScalar = ProtoScalarValue.ofNullValue(ProtoNullValue.NULL_VALUE);
            EncodeResult encoded = EncodeResult.simple(
                    EncodingId.VORTEX_CONSTANT, MemorySegment.ofArray(nullScalar.encode()));
            long rowCount = 1_000L;

            // When
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, rowCount, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);

            // Then — must be NullArray, not LazyConstant*(value=0) or LazyConstantBool(false)
            assertThat(result).isInstanceOf(NullArray.class);
            assertThat(result.length()).isEqualTo(rowCount);
        }
    }
}
