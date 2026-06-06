package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: encode then decode is lossless for unsigned integer types.
class BitpackedEncodingTest {


    @Nested
    class Encode {

        static Stream<Arguments> u32Arrays() {
            return Stream.of(
                    Arguments.of("empty", new int[]{}),
                    Arguments.of("single", new int[]{0}),
                    Arguments.of("all-zeros", new int[]{0, 0, 0, 0, 0}),
                    Arguments.of("small-values", new int[]{1, 2, 3, 4, 5, 6, 7}),
                    Arguments.of("mixed", new int[]{0, 7, 63, 255, 1023, 65535}),
                    Arguments.of("max-unsigned", new int[]{-1, -1, -1}) // 0xFFFFFFFF
            );
        }

        static Stream<Arguments> u64Arrays() {
            return Stream.of(
                    Arguments.of("empty", new long[]{}),
                    Arguments.of("single", new long[]{0L}),
                    Arguments.of("small-values", new long[]{1L, 2L, 3L, 4L, 5L}),
                    Arguments.of("large-values", new long[]{0L, 0xFFFFL, 0xFFFFFFL, 0xFFFFFFFFL})
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("u32Arrays")
        void encodeDecode_u32_isLossless(String name, int[] data) {
            // Given
            var sut = new BitpackedEncoding();
            EncodingRegistry registry = TestRegistry.of(sut);

            // When
            EncodeResult encoded = sut.encode(DTypes.U32, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.U32, registry);
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            var le = PTypeIO.LE_INT;
            for (int i = 0; i < data.length; i++) {
                assertThat(result.buffer(0).get(le, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("u64Arrays")
        void encodeDecode_u64_isLossless(String name, long[] data) {
            // Given
            var sut = new BitpackedEncoding();
            EncodingRegistry registry = TestRegistry.of(sut);

            // When
            EncodeResult encoded = sut.encode(DTypes.U64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.U64, registry);
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            var le = PTypeIO.LE_LONG;
            for (int i = 0; i < data.length; i++) {
                assertThat(result.buffer(0).get(le, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_i32_metadata_bitWidth_isNonZero() throws Exception {
            // Given — max value 5 needs 3 bits; if tag drifts, bit_width reads as 0 (proto3 default)
            int[] data = {1, 2, 3, 4, 5};
            BitpackedEncoding sut = new BitpackedEncoding();

            // When
            EncodeResult result = sut.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            EncodingProtos.BitPackedMetadata meta =
                    EncodingProtos.BitPackedMetadata.parseFrom(result.rootNode().metadata().duplicate());

            // Then
            assertThat(meta.getBitWidth()).isGreaterThan(0);
        }
    }
}
