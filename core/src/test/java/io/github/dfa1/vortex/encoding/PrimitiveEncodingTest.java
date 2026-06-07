package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: encode then decode is lossless for all primitive types and array sizes.
class PrimitiveEncodingTest {

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
            // Given
            DType dtype = new DType.Primitive(PType.I64, false);
            var sut = new PrimitiveEncoding();
            EncodingRegistry registry = TestRegistry.of(sut);

            // When
            EncodeResult encoded = sut.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, dtype, registry);
            Array result = sut.decode(ctx);

            // Then — roundtrip lossless
            assertThat(result.length()).isEqualTo(data.length);
            var le = PTypeIO.LE_LONG;
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(le, (long) i * 8)).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @MethodSource("intArrays")
        void encodeDecode_i32_isLossless(int[] data) {
            // Given
            DType dtype = new DType.Primitive(PType.I32, false);
            var sut = new PrimitiveEncoding();
            EncodingRegistry registry = TestRegistry.of(sut);

            // When
            EncodeResult encoded = sut.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, dtype, registry);
            Array result = sut.decode(ctx);

            // Then — roundtrip lossless
            assertThat(result.length()).isEqualTo(data.length);
            var le = PTypeIO.LE_INT;
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(le, (long) i * 4)).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @MethodSource("doubleArrays")
        void encodeDecode_f64_isLossless(double[] data) {
            // Given
            DType dtype = new DType.Primitive(PType.F64, false);
            var sut = new PrimitiveEncoding();
            EncodingRegistry registry = TestRegistry.of(sut);

            // When
            EncodeResult encoded = sut.encode(dtype, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, dtype, registry);
            Array result = sut.decode(ctx);

            // Then — roundtrip lossless
            assertThat(result.length()).isEqualTo(data.length);
            var le = PTypeIO.LE_DOUBLE;
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(le, (long) i * 8)).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @MethodSource("longArrays")
        void encodedSize_equalsBytesInBuffer(long[] data) {
            // Given
            DType dtype = new DType.Primitive(PType.I64, false);
            var sut = new PrimitiveEncoding();

            // When
            EncodeResult encoded = sut.encode(dtype, data, EncodeTestHelper.testCtx());

            // Then — no compression: wire size = n * elemBytes
            long totalBytes = encoded.buffers().stream().mapToLong(java.lang.foreign.MemorySegment::byteSize).sum();
            assertThat(totalBytes).isEqualTo((long) data.length * 8);
        }
    }

    @Nested
    class Decode {

        @Test
        void decode_withValidityChild_returnsMaskedArray() {
            // Given — 4 I32 values; positions 1 and 3 are null (validity bitmap: 0b00000101 = 0x05)
            int[] raw = {10, 0, 20, 0};   // garbage at null positions
            MemorySegment valuesSeg = TestSegments.leInts(raw);
            MemorySegment validitySeg = MemorySegment.ofArray(new byte[]{0x05}); // bits 0,2 set

            ArrayNode validityNode = ArrayNode.of(
                    EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1}, ArrayStats.empty());
            ArrayNode primNode = ArrayNode.of(
                    EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[]{validityNode}, new int[]{0}, ArrayStats.empty());

            EncodingRegistry registry = TestRegistry.of(new PrimitiveEncoding(), new BoolEncoding());

            DType dtype = new DType.Primitive(PType.I32, false);
            DecodeContext ctx = new DecodeContext(
                    primNode, dtype, raw.length,
                    new MemorySegment[]{valuesSeg, validitySeg},
                    registry, Arena.global());

            PrimitiveEncoding sut = new PrimitiveEncoding();

            // When
            Array result = sut.decode(ctx);

            // Then — returns MaskedArray; only valid positions are usable
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
            // Given — 3 I32 values; no validity child
            int[] raw = {1, 2, 3};
            MemorySegment valuesSeg = TestSegments.leInts(raw);

            ArrayNode primNode = ArrayNode.of(
                    EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0}, ArrayStats.empty());

            EncodingRegistry registry = TestRegistry.of(new PrimitiveEncoding());

            DType dtype = new DType.Primitive(PType.I32, false);
            DecodeContext ctx = new DecodeContext(
                    primNode, dtype, raw.length,
                    new MemorySegment[]{valuesSeg},
                    registry, Arena.global());

            PrimitiveEncoding sut = new PrimitiveEncoding();

            // When
            Array result = sut.decode(ctx);

            // Then — plain array, not MaskedArray
            assertThat(result).isInstanceOf(IntArray.class);
        }
    }
}
