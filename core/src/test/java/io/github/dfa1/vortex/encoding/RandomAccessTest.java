package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.LongArray;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies that decoded arrays support correct random-order element access.
///
/// The O(1) random-access claim in docs/explanation.md holds only if every
/// position decodes independently. This test catches encoder bugs where
/// value[N] accidentally depends on value[N-1] — e.g. an unapplied delta,
/// an off-by-one in residual accumulation, or a stateful read cursor that
/// advances forward and gives wrong values when accessed out of order.
///
/// Data: {@code value[i] = i * 1_000_003 + 7} — every position unique,
/// prime multiplier prevents accidental aliasing.
/// Access orders: reverse (N-1…0) and seeded random — the combination
/// catches symmetric bugs that reverse alone might miss.
class RandomAccessTest {

    private static final int N = 1024;
    private static final long SEED = 0xDEADBEEFL;

    static Stream<Arguments> encodings() {
        return Stream.of(
                Arguments.of("Primitive", new PrimitiveEncoding(), DTypes.I64),
                Arguments.of("BitpackedU64", new BitpackedEncoding(), DTypes.U64),
                Arguments.of("FrameOfReference", new FrameOfReferenceEncoding(), DTypes.I64)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encodings")
    void randomOrderAccess_matchesForwardOrder(String name, Encoding sut, DType dtype) {
        // Given — unique value per position, no two rows the same
        long[] original = new long[N];
        for (int i = 0; i < N; i++) {
            original[i] = (long) i * 1_000_003L + 7L;
        }

        EncodingRegistry registry = TestRegistry.withPrimitive(sut);
        EncodeResult encoded = sut.encode(dtype, original);
        DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, N, dtype, registry);

        // When
        Array array = sut.decode(ctx);
        LongArray result = (LongArray) array;

        // Then — reverse order
        for (int i = N - 1; i >= 0; i--) {
            assertThat(result.getLong(i))
                    .as("reverse access at index %d", i)
                    .isEqualTo(original[i]);
        }

        // Then — random order (seeded for reproducibility)
        Random rng = new Random(SEED);
        for (int check = 0; check < N; check++) {
            int i = rng.nextInt(N);
            assertThat(result.getLong(i))
                    .as("random access at index %d", i)
                    .isEqualTo(original[i]);
        }
    }
}
