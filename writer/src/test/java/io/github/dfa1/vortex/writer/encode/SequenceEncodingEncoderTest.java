package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.Float16Array;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.encoding.EncodeResult;
import io.github.dfa1.vortex.encoding.EncodeTestHelper;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.proto.SequenceMetadata;
import io.github.dfa1.vortex.reader.decode.SequenceEncodingDecoder;
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

class SequenceEncodingEncoderTest {

    private static final SequenceEncodingEncoder ENCODER = new SequenceEncodingEncoder();
    private static final SequenceEncodingDecoder DECODER = new SequenceEncodingDecoder();

    @Nested
    class Encode {

        private static DecodeContext encodeResultToCtx(EncodeResult result, DType dtype, long n) {
            ByteBuffer meta = result.rootNode().metadata();
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_SEQUENCE, meta, new ArrayNode[0], new int[0], null);
            return new DecodeContext(node, dtype, n, new MemorySegment[0], ReadRegistry.empty(), Arena.ofAuto());
        }

        @Test
        void encodingId_isVortexSequence() {
            assertThat(ENCODER.encodingId()).isEqualTo(EncodingId.VORTEX_SEQUENCE);
        }

        @Test
        void encode_i64_roundTrips() {
            long[] data = {10L, 12L, 14L, 16L};
            EncodeResult result = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = encodeResultToCtx(result, DTypes.I64, data.length);
            LongArray decoded = (LongArray) DECODER.decode(ctx);

            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getLong(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_f64_roundTrips() {
            double[] data = {1.0, 1.5, 2.0, 2.5};
            EncodeResult result = ENCODER.encode(DTypes.F64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = encodeResultToCtx(result, DTypes.F64, data.length);
            DoubleArray decoded = (DoubleArray) DECODER.decode(ctx);

            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getDouble(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_f16_roundTrips() {
            short[] data = {Float.floatToFloat16(0.0f), Float.floatToFloat16(1.0f), Float.floatToFloat16(2.0f)};
            EncodeResult result = ENCODER.encode(DTypes.F16, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = encodeResultToCtx(result, DTypes.F16, data.length);
            Float16Array decoded = (Float16Array) DECODER.decode(ctx);

            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getFloat(i)).as("index %d", i).isEqualTo(Float.float16ToFloat(data[i]));
            }
        }

        @Test
        void encode_nonArithmeticSequence_throwsVortexException() {
            long[] data = {1L, 2L, 4L};
            assertThatThrownBy(() -> ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void encode_nonPrimitiveDtype_throwsVortexException() {
            assertThatThrownBy(() -> ENCODER.encode(new DType.Utf8(false), new long[]{1L}, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class Decode {

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
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_SEQUENCE,
                    ByteBuffer.wrap(meta), new ArrayNode[0], new int[0], null);
            return new DecodeContext(node, dtype, n, new MemorySegment[0], ReadRegistry.empty(), Arena.ofAuto());
        }

        private static byte[] intMeta(long base, long mul) {
            return new SequenceMetadata(ScalarValue.ofInt64Value(base), ScalarValue.ofInt64Value(mul)).encode();
        }

        private static byte[] f64Meta(double base, double mul) {
            return new SequenceMetadata(ScalarValue.ofF64Value(base), ScalarValue.ofF64Value(mul)).encode();
        }

        private static byte[] f32Meta(float base, float mul) {
            return new SequenceMetadata(ScalarValue.ofF32Value(base), ScalarValue.ofF32Value(mul)).encode();
        }

        private static byte[] f16Meta(short baseShort, short mulShort) {
            return new SequenceMetadata(
                    ScalarValue.ofF16Value(Short.toUnsignedLong(baseShort)),
                    ScalarValue.ofF16Value(Short.toUnsignedLong(mulShort))).encode();
        }

        @ParameterizedTest
        @MethodSource("i64Sequences")
        void decode_i64_generatesCorrectSequence(long base, long mul, long[] expected) {
            DecodeContext ctx = makeCtx(intMeta(base, mul), DTypes.I64, expected.length);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(expected.length);
            LongArray longArray = (LongArray) result;
            for (int i = 0; i < expected.length; i++) {
                assertThat(longArray.getLong(i)).as("index %d", i).isEqualTo(expected[i]);
            }
        }

        @ParameterizedTest
        @MethodSource("i32Sequences")
        void decode_i32_generatesCorrectSequence(long base, long mul, int[] expected) {
            DecodeContext ctx = makeCtx(intMeta(base, mul), DTypes.I32, expected.length);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(expected.length);
            IntArray intArray = (IntArray) result;
            for (int i = 0; i < expected.length; i++) {
                assertThat(intArray.getInt(i)).as("index %d", i).isEqualTo(expected[i]);
            }
        }

        @Test
        void decode_f64_generatesCorrectSequence() {
            DecodeContext ctx = makeCtx(f64Meta(1.0, 0.5), DTypes.F64, 4);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(4);
            DoubleArray doubleArray = (DoubleArray) result;
            assertThat(doubleArray.getDouble(0)).isEqualTo(1.0);
            assertThat(doubleArray.getDouble(1)).isEqualTo(1.5);
            assertThat(doubleArray.getDouble(2)).isEqualTo(2.0);
            assertThat(doubleArray.getDouble(3)).isEqualTo(2.5);
        }

        @Test
        void decode_f32_generatesCorrectSequence() {
            DecodeContext ctx = makeCtx(f32Meta(0.0f, 1.0f), DTypes.F32, 3);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(3);
            FloatArray floatArray = (FloatArray) result;
            assertThat(floatArray.getFloat(0)).isEqualTo(0.0f);
            assertThat(floatArray.getFloat(1)).isEqualTo(1.0f);
            assertThat(floatArray.getFloat(2)).isEqualTo(2.0f);
        }

        @Test
        void decode_emptySequence_returnsZeroLengthArray() {
            DecodeContext ctx = makeCtx(intMeta(0, 1), DTypes.I64, 0);
            Array result = DECODER.decode(ctx);
            assertThat(result.length()).isZero();
        }

        @Test
        void decode_missingMetadata_throwsVortexException() {
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_SEQUENCE, null, new ArrayNode[0], new int[0], null);
            DecodeContext ctx = new DecodeContext(node, DTypes.I64, 3, new MemorySegment[0], ReadRegistry.empty(), Arena.ofAuto());

            assertThatThrownBy(() -> DECODER.decode(ctx)).isInstanceOf(VortexException.class);
        }

        @Test
        void decode_f16_generatesCorrectSequence() {
            short baseShort = Float.floatToFloat16(0.0f);
            short mulShort = Float.floatToFloat16(1.0f);
            byte[] meta = f16Meta(baseShort, mulShort);
            DecodeContext ctx = makeCtx(meta, DTypes.F16, 3);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(3);
            Float16Array f16Array = (Float16Array) result;
            assertThat(f16Array.getFloat(0)).isEqualTo(0.0f);
            assertThat(f16Array.getFloat(1)).isEqualTo(1.0f);
            assertThat(f16Array.getFloat(2)).isEqualTo(2.0f);
        }

        @Test
        void decode_nonPrimitiveDtype_throwsVortexException() {
            DType utf8 = new DType.Utf8(false);
            DecodeContext ctx = makeCtx(intMeta(0, 1), utf8, 3);
            assertThatThrownBy(() -> DECODER.decode(ctx)).isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class Metadata {

        @Test
        void encode_i64_metadata_base_andMultiplier_areSet() throws Exception {
            long[] data = {10L, 12L, 14L, 16L};
            EncodeResult result = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            MemorySegment metaSeg = MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
            SequenceMetadata meta = SequenceMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(meta.base()).isNotNull();
            assertThat(meta.multiplier()).isNotNull();
            assertThat(meta.base().int64_value()).isEqualTo(10L);
            assertThat(meta.multiplier().int64_value()).isEqualTo(2L);
        }
    }
}
