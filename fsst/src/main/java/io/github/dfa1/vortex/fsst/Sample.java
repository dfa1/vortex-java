package io.github.dfa1.vortex.fsst;

/// The training sample, drawn as Rust's `fsst-rs` does (`builder.rs`'s `make_sample`, itself the C++
/// reference's): the rows as they are when their bytes total under [#TARGET_SAMPLE_BYTES], else
/// [#LINE_BYTES]-byte lines of randomly picked non-empty rows until the target is reached. The
/// pick is a fixed hash sequence ([#hash(long)]), not a seeded generator, so a given corpus always
/// draws the same sample, and the same one Rust draws.
///
/// A line is a slice of one row, and a short corpus keeps its empty rows as empty lines: training
/// skips lines by their index, so the indices have to be Rust's too.
final class Sample {

    /// A corpus of at least this many bytes is sampled down to about this many: Rust's
    /// `FSST_SAMPLETARGET`.
    static final int TARGET_SAMPLE_BYTES = 1 << 14;

    /// Longest line a sample takes from one row: Rust's `FSST_SAMPLELINE`.
    static final int LINE_BYTES = 512;

    /// Rust's seed of the sample's hash sequence.
    private static final long SAMPLE_SEED = 4637947L;

    /// The generations' sample fractions, out of [#FRACTION_DENOMINATOR]: Rust's `GENERATIONS`.
    static final int[] FRACTION_NUMERATORS = {8, 38, 68, 98, 128};

    /// The denominator of the sample fractions.
    static final int FRACTION_DENOMINATOR = 128;

    private final byte[] bytes;
    private final int[] lineBoundaries;
    private final int lineCount;
    private final boolean sampled;

    private Sample(byte[] bytes, int[] lineBoundaries, int lineCount, boolean sampled) {
        this.bytes = bytes;
        this.lineBoundaries = lineBoundaries;
        this.lineCount = lineCount;
        this.sampled = sampled;
    }

    /// Draws the sample of rows held one array each.
    ///
    /// @param rows the corpus rows, never `null`; a row may be empty
    /// @return the sample
    static Sample draw(byte[][] rows) {
        return draw(rows.length, new RowAccess() {
            @Override
            public int rowLength(int row) {
                return rows[row].length;
            }

            @Override
            public void copy(int row, int start, int len, byte[] out, int outPos) {
                System.arraycopy(rows[row], start, out, outPos, len);
            }
        });
    }

    /// Draws the sample of rows held contiguously, row `i` spanning `rowBytes[rowOffsets[i],
    /// rowOffsets[i + 1])`.
    ///
    /// @param rowBytes   all rows' bytes concatenated
    /// @param rowOffsets `rowCount + 1` cumulative offsets into `rowBytes`
    /// @param rowCount   the number of rows
    /// @return the sample
    static Sample draw(byte[] rowBytes, int[] rowOffsets, int rowCount) {
        return draw(rowCount, new RowAccess() {
            @Override
            public int rowLength(int row) {
                return rowOffsets[row + 1] - rowOffsets[row];
            }

            @Override
            public void copy(int row, int start, int len, byte[] out, int outPos) {
                System.arraycopy(rowBytes, rowOffsets[row] + start, out, outPos, len);
            }
        });
    }

    private static Sample draw(int rowCount, RowAccess access) {
        long totalBytes = 0;
        for (int i = 0; i < rowCount; i++) {
            totalBytes += access.rowLength(i);
        }
        if (totalBytes < TARGET_SAMPLE_BYTES) {
            return whole(rowCount, access, (int) totalBytes);
        }
        return sampled(rowCount, access);
    }

    private static Sample whole(int rowCount, RowAccess access, int totalBytes) {
        byte[] out = new byte[totalBytes];
        int[] boundaries = new int[rowCount + 1];
        int pos = 0;
        for (int i = 0; i < rowCount; i++) {
            int len = access.rowLength(i);
            access.copy(i, 0, len, out, pos);
            pos += len;
            boundaries[i + 1] = pos;
        }
        return new Sample(out, boundaries, rowCount, false);
    }

    private static Sample sampled(int rowCount, RowAccess access) {
        // a line is at most LINE_BYTES, and the draw stops at the first line past the target
        byte[] out = new byte[TARGET_SAMPLE_BYTES + LINE_BYTES];
        int[] boundaries = new int[TARGET_SAMPLE_BYTES + 2];
        int lines = 0;
        int size = 0;
        long rnd = hash(SAMPLE_SEED);
        while (size < TARGET_SAMPLE_BYTES) {
            rnd = hash(rnd);
            int start = (int) Long.remainderUnsigned(rnd, rowCount);
            int row = firstNonEmptyFrom(start, rowCount, access);
            if (row < 0) {
                break;
            }
            int rowLength = access.rowLength(row);
            int chunks = 1 + (rowLength - 1) / LINE_BYTES;
            rnd = hash(rnd);
            int chunk = LINE_BYTES * (int) Long.remainderUnsigned(rnd, chunks);
            int len = Math.min(LINE_BYTES, rowLength - chunk);
            access.copy(row, chunk, len, out, size);
            size += len;
            lines++;
            boundaries[lines] = size;
        }
        return new Sample(out, boundaries, lines, true);
    }

    /// The first row from `start` on, wrapping around, that is not empty, or -1 when every row is.
    private static int firstNonEmptyFrom(int start, int rowCount, RowAccess access) {
        for (int i = start; i < rowCount; i++) {
            if (access.rowLength(i) > 0) {
                return i;
            }
        }
        for (int i = 0; i < start; i++) {
            if (access.rowLength(i) > 0) {
                return i;
            }
        }
        return -1;
    }

    /// Rust's `fsst_hash`, the C++ reference's `FSST_HASH`.
    ///
    /// @param value the value to hash
    /// @return its hash
    static long hash(long value) {
        return value * 2971215073L ^ value >>> 15;
    }

    private interface RowAccess {
        int rowLength(int row);

        void copy(int row, int start, int len, byte[] out, int outPos);
    }

    byte[] bytes() {
        return bytes;
    }

    int lineCount() {
        return lineCount;
    }

    int lineStart(int i) {
        return lineBoundaries[i];
    }

    int lineEnd(int i) {
        return lineBoundaries[i + 1];
    }

    /// Whether the corpus was sampled down, rather than taken whole: Rust prunes the last
    /// generation's table only for a corpus it took whole.
    boolean sampled() {
        return sampled;
    }
}
