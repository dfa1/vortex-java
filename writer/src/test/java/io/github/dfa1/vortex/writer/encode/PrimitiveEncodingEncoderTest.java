package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.encoding.EncodeResult;
import io.github.dfa1.vortex.encoding.EncodeTestHelper;
import io.github.dfa1.vortex.reader.decode.DecodeTestHelper;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.encoding.TestSegments;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PrimitiveEncodingEncoderTest {

    private static final PrimitiveEncodingEncoder ENCODER = new PrimitiveEncodingEncoder();
    private static final PrimitiveEncodingDecoder DECODER = new PrimitiveEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER);

    @Nested
    class Encode {

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
            DType dtype = new DType.Primitive(PType.I64, false);
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(data.length);
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, (long) i * 8)).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @MethodSource("intArrays")
        void encodeDecode_i32_isLossless(int[] data) {
            DType dtype = new DType.Primitive(PType.I32, false);
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(data.length);
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, (long) i * 4)).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @MethodSource("doubleArrays")
        void encodeDecode_f64_isLossless(double[] data) {
            DType dtype = new DType.Primitive(PType.F64, false);
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, dtype, REGISTRY);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(data.length);
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, (long) i * 8)).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @MethodSource("longArrays")
        void encodedSize_equalsBytesInBuffer(long[] data) {
            DType dtype = new DType.Primitive(PType.I64, false);
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());

            long totalBytes = encoded.buffers().stream().mapToLong(MemorySegment::byteSize).sum();
            assertThat(totalBytes).isEqualTo((long) data.length * 8);
        }
    }

    @Nested
    class Decode {

        @Test
        void decode_withValidityChild_returnsMaskedArray() {
            int[] raw = {10, 0, 20, 0};
            MemorySegment valuesSeg = TestSegments.leInts(raw);
            MemorySegment validitySeg = MemorySegment.ofArray(new byte[]{0x05});

            ArrayNode validityNode = ArrayNode.of(
                    EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1}, ArrayStats.empty());
            ArrayNode primNode = ArrayNode.of(
                    EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[]{validityNode}, new int[]{0}, ArrayStats.empty());

            ReadRegistry registry = TestRegistry.ofDecoders(new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

            DType dtype = new DType.Primitive(PType.I32, false);
            DecodeContext ctx = new DecodeContext(
                    primNode, dtype, raw.length,
                    new MemorySegment[]{valuesSeg, validitySeg},
                    registry, Arena.global());

            Array result = DECODER.decode(ctx);

            assertThat(result).isInstanceOf(MaskedArray.class);
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.inner()).isInstanceOf(IntArray.class);
            assertThat(masked.isValid(0)).isTrue();
            assertThat(masked.isValid(1)).isFalse();
            assertThat(masked.isValid(2)).isTrue();
            assertThat(masked.isValid(3)).isFalse();
            IntArray values = (IntArray) masked.inner();
            assertThat(values.getInt(0)).isEqualTo(10);
            assertThat(values.getInt(2)).isEqualTo(20);
        }

        @Test
        void decode_noValidityChild_returnsPlainArray() {
            int[] raw = {1, 2, 3};
            MemorySegment valuesSeg = TestSegments.leInts(raw);

            ArrayNode primNode = ArrayNode.of(
                    EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0}, ArrayStats.empty());

            DType dtype = new DType.Primitive(PType.I32, false);
            DecodeContext ctx = new DecodeContext(
                    primNode, dtype, raw.length,
                    new MemorySegment[]{valuesSeg},
                    REGISTRY, Arena.global());

            Array result = DECODER.decode(ctx);
            assertThat(result).isInstanceOf(IntArray.class);
        }
    }
}
