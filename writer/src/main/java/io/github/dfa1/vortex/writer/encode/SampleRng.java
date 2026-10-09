package io.github.dfa1.vortex.writer.encode;

/// Rust's `StdRng::seed_from_u64` (`rand` 0.10: ChaCha12) and its integer `random_range`, ported so the
/// cascade draws the very sample Rust's compressor draws.
///
/// The compressor's sample decides which scheme wins on borderline arrays, so a different (even
/// equally random) sample picks different encodings than the Rust reference for the same data.
/// Rust seeds a fresh generator with a fixed constant for every sample, which makes the draw
/// reproducible; this class reproduces the draw bit for bit.
final class SampleRng {

    /// Rust's `SAMPLE_SEED` (`vortex-compressor` `compressor/sample.rs`).
    static final long RUST_SAMPLE_SEED = 1234567890L;

    private static final int[] CONSTANTS = {0x61707865, 0x3320646e, 0x79622d32, 0x6b206574};
    private static final int DOUBLE_ROUNDS = 6;
    private static final int BLOCK_WORDS = 16;

    private final int[] key = new int[8];
    private final int[] block = new int[BLOCK_WORDS];
    private long counter;
    private int next = BLOCK_WORDS;

    /// `SeedableRng::seed_from_u64`: a PCG32 stream expands the seed into the 32-byte ChaCha key.
    ///
    /// @param seed the seed
    SampleRng(long seed) {
        long state = seed;
        for (int i = 0; i < key.length; i++) {
            state = state * 6364136223846793005L + 0xa17654e46fbe17f3L;
            int xorShifted = (int) (((state >>> 18) ^ state) >>> 27);
            int rotation = (int) (state >>> 59);
            key[i] = Integer.rotateRight(xorShifted, rotation);
        }
    }

    /// `RngCore::next_u32`.
    ///
    /// @return the next 32 random bits
    int nextInt() {
        if (next == BLOCK_WORDS) {
            refill();
        }
        return block[next++];
    }

    /// `RngCore::next_u64`: two words, the first the low half.
    ///
    /// @return the next 64 random bits
    long nextLong() {
        long low = nextInt() & 0xFFFFFFFFL;
        long high = nextInt() & 0xFFFFFFFFL;
        return high << 32 | low;
    }

    /// `random_range(low..=high)` for a `usize` whose range fits in 32 bits, which `rand` draws from
    /// a single `u32` with Canon's widening multiply rather than from a `u64`.
    ///
    /// @param low  the smallest value
    /// @param high the largest value, at least `low`
    /// @return a value in `[low, high]`
    int nextIntInclusive(int low, int high) {
        int range = high - low + 1;
        if (range == 0) {
            return nextInt();
        }
        long rangeUnsigned = range & 0xFFFFFFFFL;
        long product = (nextInt() & 0xFFFFFFFFL) * rangeUnsigned;
        long result = product >>> 32;
        long lowOrder = product & 0xFFFFFFFFL;
        if (lowOrder > (-rangeUnsigned & 0xFFFFFFFFL)) {
            long newHighOrder = ((nextInt() & 0xFFFFFFFFL) * rangeUnsigned) >>> 32;
            if (lowOrder + newHighOrder > 0xFFFFFFFFL) {
                result++;
            }
        }
        return low + (int) result;
    }

    private void refill() {
        int[] state = new int[BLOCK_WORDS];
        System.arraycopy(CONSTANTS, 0, state, 0, 4);
        System.arraycopy(key, 0, state, 4, 8);
        state[12] = (int) counter;
        state[13] = (int) (counter >>> 32);
        // words 14 and 15: the stream id, always 0 for a seeded StdRng
        int[] working = state.clone();
        for (int round = 0; round < DOUBLE_ROUNDS; round++) {
            quarterRound(working, 0, 4, 8, 12);
            quarterRound(working, 1, 5, 9, 13);
            quarterRound(working, 2, 6, 10, 14);
            quarterRound(working, 3, 7, 11, 15);
            quarterRound(working, 0, 5, 10, 15);
            quarterRound(working, 1, 6, 11, 12);
            quarterRound(working, 2, 7, 8, 13);
            quarterRound(working, 3, 4, 9, 14);
        }
        for (int i = 0; i < BLOCK_WORDS; i++) {
            block[i] = working[i] + state[i];
        }
        counter++;
        next = 0;
    }

    private static void quarterRound(int[] x, int a, int b, int c, int d) {
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }
}
