package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.reader.decode.BitpackedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.FrameOfReferenceEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies that decoded arrays support correct random-order element access.
class RandomAccessTest {

    private static final int N = 1024;
    private static final long SEED = 0xDEADBEEFL;

    static Stream<Arguments> encodings() {
        return Stream.of(
                Arguments.of("Primitive", new PrimitiveEncodingEncoder(), new PrimitiveEncodingDecoder(), DTypes.I64),
                Arguments.of("BitpackedU64", new BitpackedEncodingEncoder(), new BitpackedEncodingDecoder(), DTypes.U64),
                Arguments.of("FrameOfReference", new FrameOfReferenceEncodingEncoder(), new FrameOfReferenceEncodingDecoder(), DTypes.I64)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encodings")
    void randomOrderAccess_matchesForwardOrder(String name,
            EncodingEncoder encoder, io.github.dfa1.vortex.reader.decode.EncodingDecoder decoder, DType dtype) {
        // Given
        long[] original = new long[N];
        for (int i = 0; i < N; i++) {
            original[i] = (long) i * 1_000_003L + 7L;
        }

        ReadRegistry registry = decoder instanceof PrimitiveEncodingDecoder
                ? TestRegistry.ofDecoders(decoder)
                : TestRegistry.ofDecoders(decoder, new PrimitiveEncodingDecoder());
        EncodeResult encoded = encoder.encode(dtype, original, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, N, dtype, registry);

        // When
        Array array = decoder.decode(ctx);
        LongArray result = (LongArray) array;

        // Then
        for (int i = N - 1; i >= 0; i--) {
            assertThat(result.getLong(i))
                    .as("reverse access at index %d", i)
                    .isEqualTo(original[i]);
        }

        Random rng = new Random(SEED);
        for (int check = 0; check < N; check++) {
            int i = rng.nextInt(N);
            assertThat(result.getLong(i))
                    .as("random access at index %d", i)
                    .isEqualTo(original[i]);
        }
    }
}
