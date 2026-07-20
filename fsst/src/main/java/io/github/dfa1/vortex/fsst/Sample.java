package io.github.dfa1.vortex.fsst;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/// A fixed, byte-size-bounded training sample drawn deterministically from a corpus of rows.
///
/// FSST training does not scan the whole input on every generation. Instead it draws one bounded
/// sample up front — targeting [#TARGET_SAMPLE_BYTES] bytes assembled from contiguous chunks of at
/// most [#MAX_CHUNK_BYTES] bytes, each taken from a randomly chosen row — and then each of the five
/// training generations replays only a growing fraction of that same sample. This replaces the old
/// `FsstEncodingEncoder`'s string-count-bounded, always-full-sample scheme, which on long-string
/// columns pulled in far more training bytes than the reference intends for no compression benefit
/// (issue #287). Sampling by bytes keeps training cost proportional to the sample size regardless of
/// how long individual rows are.
///
/// The sample is held as a single concatenated byte array with explicit chunk boundaries, because a
/// symbol must never be counted across a chunk boundary: chunks come from unrelated rows, so a
/// "symbol" spanning two chunks would be an artifact of sampling, not a real substring of any row.
///
/// Drawing is deterministic given a seed: the same rows and seed always produce a byte-identical
/// sample, which is what makes the whole trained table reproducible across runs (the encoder's
/// output must be byte-identical for a given input).
final class Sample {

    /// Target total sample size in bytes. The Rust reference `spiraldb/fsst` targets ~16KB; a
    /// sample this size is large enough to learn a stable table while keeping every generation's
    /// compress-count pass cheap.
    static final int TARGET_SAMPLE_BYTES = 16 * 1024;

    /// Maximum length of a single contiguous chunk drawn from one row. Long rows contribute several
    /// chunks; short rows contribute one (truncated to the row length). Bounding chunk length keeps
    /// the sample spread across many rows rather than dominated by a single long one.
    static final int MAX_CHUNK_BYTES = 512;

    /// Denominator for the per-generation growing sample fraction. Each generation replays
    /// `SAMPLE_FRACTION_NUMERATORS[gen] / SAMPLE_FRACTION_DENOMINATOR` of the sample's chunks.
    static final int SAMPLE_FRACTION_DENOMINATOR = 128;

    /// Per-generation numerators over [#SAMPLE_FRACTION_DENOMINATOR]. Early generations see only a
    /// small fraction of the sample (the table is still small and low-quality, so compressing more
    /// bytes buys nothing), ramping to the full sample on the last generation once the table has
    /// mostly converged. These are the Rust reference's fractions: ~6%, ~30%, ~53%, ~77%, 100%.
    static final int[] SAMPLE_FRACTION_NUMERATORS = {8, 38, 68, 98, 128};

    private final byte[] bytes;
    private final int[] chunkStarts;
    private final int[] chunkEnds;

    private Sample(byte[] bytes, int[] chunkStarts, int[] chunkEnds) {
        this.bytes = bytes;
        this.chunkStarts = chunkStarts;
        this.chunkEnds = chunkEnds;
    }

    /// Draws a deterministic byte-size-bounded sample from `rows`.
    ///
    /// Rows are selected at random; each selection contributes one contiguous chunk of at most
    /// [#MAX_CHUNK_BYTES] bytes, starting at a random offset within the chosen row. Empty rows are
    /// skipped. Drawing stops once [#TARGET_SAMPLE_BYTES] bytes have been collected, so a corpus
    /// smaller than the target is used in full. When every row is empty the sample is empty.
    ///
    /// @param rows the corpus rows, each a raw byte array (never `null`, but individually may be
    ///             empty)
    /// @param seed the PRNG seed; the same rows and seed yield a byte-identical sample
    /// @return a sample of up to [#TARGET_SAMPLE_BYTES] bytes split into per-row chunks
    @SuppressWarnings("java:S2245") // Deterministic PRNG is the contract: training samples must be
                                    // reproducible across builds. No security boundary here.
    static Sample draw(byte[][] rows, long seed) {
        long totalBytes = 0;
        int nonEmptyRows = 0;
        for (byte[] row : rows) {
            totalBytes += row.length;
            if (row.length > 0) {
                nonEmptyRows++;
            }
        }
        if (nonEmptyRows == 0) {
            return new Sample(new byte[0], new int[0], new int[0]);
        }

        int target = (int) Math.min(TARGET_SAMPLE_BYTES, totalBytes);
        byte[] out = new byte[target];
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        Random rng = new Random(seed);
        int outPos = 0;

        // Bound the number of empty-row skips so an all-but-one-empty corpus cannot spin forever;
        // once we have drawn from the one non-empty row enough times to fill the target, we stop.
        while (outPos < target) {
            int row = rng.nextInt(rows.length);
            byte[] source = rows[row];
            if (source.length == 0) {
                continue;
            }
            int remaining = target - outPos;
            int chunkLen = Math.min(Math.min(MAX_CHUNK_BYTES, source.length), remaining);
            int maxStart = source.length - chunkLen;
            int start = maxStart == 0 ? 0 : rng.nextInt(maxStart + 1);
            System.arraycopy(source, start, out, outPos, chunkLen);
            starts.add(outPos);
            ends.add(outPos + chunkLen);
            outPos += chunkLen;
        }

        return new Sample(out, toIntArray(starts), toIntArray(ends));
    }

    /// Returns the concatenated sample bytes. Chunk boundaries in [#chunkStart(int)] /
    /// [#chunkEnd(int)] delimit the parts that came from distinct rows.
    ///
    /// @return the sample bytes; never `null`, possibly empty
    byte[] bytes() {
        return bytes;
    }

    /// Returns the number of chunks the sample is split into. Generation `gen` replays only the
    /// first [#chunkCountForGeneration(int)] of these.
    ///
    /// @return the total chunk count
    int chunkCount() {
        return chunkStarts.length;
    }

    /// Returns the start offset (inclusive) of chunk `i` within [#bytes()].
    ///
    /// @param i the chunk index, in `0 .. chunkCount() - 1`
    /// @return the chunk's start offset into the sample bytes
    int chunkStart(int i) {
        return chunkStarts[i];
    }

    /// Returns the end offset (exclusive) of chunk `i` within [#bytes()].
    ///
    /// @param i the chunk index, in `0 .. chunkCount() - 1`
    /// @return the chunk's end offset into the sample bytes
    int chunkEnd(int i) {
        return chunkEnds[i];
    }

    /// Returns how many leading chunks generation `gen` (0-based) should replay, applying that
    /// generation's growing sample fraction. Generation 0 sees roughly 6% of the chunks, the last
    /// generation sees them all. At least one chunk is always replayed when the sample is non-empty,
    /// so no generation trains on nothing.
    ///
    /// @param gen the 0-based generation index, in `0 .. SAMPLE_FRACTION_NUMERATORS.length - 1`
    /// @return the number of leading chunks to replay for that generation
    int chunkCountForGeneration(int gen) {
        int total = chunkCount();
        if (total == 0) {
            return 0;
        }
        long scaled = (long) total * SAMPLE_FRACTION_NUMERATORS[gen] / SAMPLE_FRACTION_DENOMINATOR;
        return (int) Math.max(1, Math.min(total, scaled));
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }
}
