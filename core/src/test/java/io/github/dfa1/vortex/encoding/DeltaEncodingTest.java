package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: encode then decode is lossless; monotonic sequences compress smaller than raw.
class DeltaEncodingTest {

    @Nested
    class Encode {

        static Stream<long[]> i64Arrays() {
            return Stream.of(
                    new long[]{0},
                    new long[]{Long.MIN_VALUE},
                    new long[]{0, 1, 2, 3, 4, 5, 6, 7},
                    new long[]{100, 200, 300, 400, 500},
                    new long[]{-100, -50, 0, 50, 100},
                    new long[]{1000, 999, 998, 997, 996}
            );
        }

        static Stream<int[]> i32Arrays() {
            return Stream.of(
                    new int[]{0},
                    new int[]{Integer.MIN_VALUE},
                    new int[]{0, 1, 2, 3, 4, 5, 6, 7},
                    new int[]{10, 20, 30, 40, 50},
                    new int[]{-5, -4, -3, -2, -1, 0}
            );
        }

        static Stream<Arguments> monotoneI64Arrays() {
            return Stream.of(
                    // strictly monotone with constant delta → very compressible
                    Arguments.of("ascending-1", new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}),
                    Arguments.of("ascending-100", new long[]{0, 100, 200, 300, 400, 500, 600, 700, 800, 900}),
                    Arguments.of("descending", new long[]{1000, 999, 998, 997, 996, 995, 994, 993, 992, 991})
            );
        }

        @ParameterizedTest
        @MethodSource("i64Arrays")
        void encodeDecode_i64_isLossless(long[] data) {
            // Given
            var sut = new DeltaEncoding();
            EncodingRegistry registry = TestRegistry.withPrimitive(sut);

            // When
            EncodeResult encoded = sut.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I64, registry);
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            var le = PTypeIO.LE_LONG;
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(le, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @MethodSource("i32Arrays")
        void encodeDecode_i32_isLossless(int[] data) {
            // Given
            var sut = new DeltaEncoding();
            EncodingRegistry registry = TestRegistry.withPrimitive(sut);

            // When
            EncodeResult encoded = sut.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I32, registry);
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            var le = PTypeIO.LE_INT;
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(le, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("monotoneI64Arrays")
        void encodeDecode_monotoneI64_isLossless(String name, long[] data) {
            // Given
            var sut = new DeltaEncoding();
            EncodingRegistry registry = TestRegistry.withPrimitive(sut);

            // When
            EncodeResult encoded = sut.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I64, registry);
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            var le = PTypeIO.LE_LONG;
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(le, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_i64_metadata_deltasLen_isNonZero() throws Exception {
            // Given — n=5 values produce n-1=4 deltas; if tag drifts, deltas_len reads as 0
            long[] data = {10L, 20L, 30L, 40L, 50L};
            DeltaEncoding sut = new DeltaEncoding();

            // When
            EncodeResult result = sut.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            EncodingProtos.DeltaMetadata meta =
                    EncodingProtos.DeltaMetadata.parseFrom(result.rootNode().metadata().duplicate());

            // Then
            assertThat(meta.getDeltasLen()).isGreaterThan(0);
        }
    }
}
