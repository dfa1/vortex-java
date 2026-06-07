package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
            EncodingRegistry registry = TestRegistry.of(sut);

            // When
            EncodeResult encoded = sut.encode(DTypes.I64, new long[]{42L}, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, rowCount, DTypes.I64, registry);
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(rowCount);
            // Constant encoding must not materialize the full array: the backing buffer must
            // hold exactly one element. Before fix: buffer is rowCount * 8 bytes.
            assertThat(result.segment().byteSize())
                    .as("constant encoding must not allocate O(rowCount) memory")
                    .isEqualTo(Long.BYTES);
            assertThat(result.segment().get(PTypeIO.LE_LONG, 0L)).isEqualTo(42L);
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
            EncodingRegistry registry = TestRegistry.of(sut);
            var le = PTypeIO.LE_INT;

            // When
            EncodeResult encoded = sut.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I32, registry);
            Array result = sut.decode(ctx);

            // Then — buffer holds one element; logical length is n
            assertThat(result.length()).isEqualTo(data.length);
            assertThat(result.segment().byteSize()).isEqualTo(Integer.BYTES);
            assertThat(result.segment().get(le, 0L)).isEqualTo(data[0]);
        }

        @ParameterizedTest
        @MethodSource("i64ConstantArrays")
        void encodeDecode_i64_isLossless(long[] data) {
            // Given
            var sut = new ConstantEncoding();
            EncodingRegistry registry = TestRegistry.of(sut);
            var le = PTypeIO.LE_LONG;

            // When
            EncodeResult encoded = sut.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I64, registry);
            Array result = sut.decode(ctx);

            // Then — buffer holds one element; logical length is n
            assertThat(result.length()).isEqualTo(data.length);
            assertThat(result.segment().byteSize()).isEqualTo(Long.BYTES);
            assertThat(result.segment().get(le, 0L)).isEqualTo(data[0]);
        }
    }
}
