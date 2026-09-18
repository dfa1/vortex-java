package io.github.dfa1.vortex.fsst;

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

    /// Chunk boundaries as one array of `chunkCount + 1` cumulative offsets: chunk `i` spans
    /// `[chunkBoundaries[i], chunkBoundaries[i + 1])`. Chunks are written back-to-back in
    /// [#draw(byte[][], long)], so a chunk's end always equals the next chunk's start — a separate
    /// ends array would just duplicate `chunkBoundaries[1..]`. The array may be over-allocated
    /// (sized for the worst case of every chunk being 1 byte); only indices `0 .. chunkCount` are
    /// valid, so `chunkCount` is tracked separately rather than trimming the array to size.
    private final int[] chunkBoundaries;
    private final int chunkCount;

    private Sample(byte[] bytes, int[] chunkBoundaries, int chunkCount) {
        this.bytes = bytes;
        this.chunkBoundaries = chunkBoundaries;
        this.chunkCount = chunkCount;
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
        // Pre-filter non-empty row indices once, up front: drawing then picks uniformly among only
        // these `nonEmptyRows` candidates, an O(1) selection per chunk. The alternative — drawing a
        // row index from the full `rows.length` range and re-drawing on an empty hit — is an
        // unbounded rejection sampler whose expected draw count is quadratic in the corpus's
        // sparsity (e.g. a 1M-row, 0.01%-non-empty corpus), not the bounded skip a prior version of
        // this comment claimed.
        int[] nonEmptyRowIndices = new int[rows.length];
        int nonEmptyRows = 0;
        for (int i = 0; i < rows.length; i++) {
            int len = rows[i].length;
            totalBytes += len;
            if (len > 0) {
                nonEmptyRowIndices[nonEmptyRows++] = i;
            }
        }
        if (nonEmptyRows == 0) {
            return new Sample(new byte[0], new int[]{0}, 0);
        }

        int target = (int) Math.min(TARGET_SAMPLE_BYTES, totalBytes);
        byte[] out = new byte[target];
        // Every chunk is at least 1 byte, so `target` chunks is the worst-case count (plus one for
        // the leading 0 boundary); no growable, boxing list needed.
        int[] boundaries = new int[target + 1];
        int chunkCount = 0;
        Random rng = new Random(seed);
        int outPos = 0;

        while (outPos < target) {
            byte[] source = rows[nonEmptyRowIndices[rng.nextInt(nonEmptyRows)]];
            int remaining = target - outPos;
            int chunkLen = Math.min(Math.min(MAX_CHUNK_BYTES, source.length), remaining);
            int maxStart = source.length - chunkLen;
            int start = maxStart == 0 ? 0 : rng.nextInt(maxStart + 1);
            System.arraycopy(source, start, out, outPos, chunkLen);
            outPos += chunkLen;
            chunkCount++;
            boundaries[chunkCount] = outPos;
        }

        return new Sample(out, boundaries, chunkCount);
    }

    /// Draws a deterministic byte-size-bounded sample from rows held contiguously, where row `i`
    /// spans `rowBytes[rowOffsets[i], rowOffsets[i + 1])`.
    ///
    /// Behaves exactly like [#draw(byte[][], long)] — same selection order, same chunk lengths,
    /// same bytes for the same seed — but reads its rows out of one shared array instead of one
    /// `byte[]` object per row, so a caller holding its corpus contiguously never has to explode it
    /// into per-row arrays just to train.
    ///
    /// @param rowBytes   all rows' bytes concatenated
    /// @param rowOffsets `rowCount + 1` cumulative offsets into `rowBytes`
    /// @param rowCount   the number of rows
    /// @param seed       the PRNG seed; the same rows and seed yield a byte-identical sample
    /// @return a sample of up to [#TARGET_SAMPLE_BYTES] bytes split into per-row chunks
    @SuppressWarnings("java:S2245") // Deterministic PRNG is the contract, as in the byte[][] overload.
    static Sample draw(byte[] rowBytes, int[] rowOffsets, int rowCount, long seed) {
        long totalBytes = 0;
        int[] nonEmptyRowIndices = new int[rowCount];
        int nonEmptyRows = 0;
        for (int i = 0; i < rowCount; i++) {
            int len = rowOffsets[i + 1] - rowOffsets[i];
            totalBytes += len;
            if (len > 0) {
                nonEmptyRowIndices[nonEmptyRows++] = i;
            }
        }
        if (nonEmptyRows == 0) {
            return new Sample(new byte[0], new int[]{0}, 0);
        }

        int target = (int) Math.min(TARGET_SAMPLE_BYTES, totalBytes);
        byte[] out = new byte[target];
        int[] boundaries = new int[target + 1];
        int chunkCount = 0;
        Random rng = new Random(seed);
        int outPos = 0;

        while (outPos < target) {
            int row = nonEmptyRowIndices[rng.nextInt(nonEmptyRows)];
            int rowStart = rowOffsets[row];
            int rowLen = rowOffsets[row + 1] - rowStart;
            int remaining = target - outPos;
            int chunkLen = Math.min(Math.min(MAX_CHUNK_BYTES, rowLen), remaining);
            int maxStart = rowLen - chunkLen;
            int start = maxStart == 0 ? 0 : rng.nextInt(maxStart + 1);
            System.arraycopy(rowBytes, rowStart + start, out, outPos, chunkLen);
            outPos += chunkLen;
            chunkCount++;
            boundaries[chunkCount] = outPos;
        }

        return new Sample(out, boundaries, chunkCount);
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
        return chunkCount;
    }

    /// Returns the start offset (inclusive) of chunk `i` within [#bytes()].
    ///
    /// @param i the chunk index, in `0 .. chunkCount() - 1`
    /// @return the chunk's start offset into the sample bytes
    int chunkStart(int i) {
        return chunkBoundaries[i];
    }

    /// Returns the end offset (exclusive) of chunk `i` within [#bytes()].
    ///
    /// @param i the chunk index, in `0 .. chunkCount() - 1`
    /// @return the chunk's end offset into the sample bytes
    int chunkEnd(int i) {
        return chunkBoundaries[i + 1];
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
}
