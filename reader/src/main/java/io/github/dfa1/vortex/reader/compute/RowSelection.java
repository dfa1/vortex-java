package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.array.Mask;

import java.util.function.LongPredicate;

/// The rows of one chunk a filter selects: a row test, plus a walk to the next selected row, which a
/// selection held as a bitmap answers 64 rows at a time, as [java.util.BitSet#nextSetBit(int)] does,
/// instead of testing each row.
public interface RowSelection extends LongPredicate {

    /// Returns the first selected row at or after `from`.
    ///
    /// @param from the zero-based row to start at
    /// @return the selected row, or `-1` when no row from `from` on is selected
    long nextSelected(long from);

    /// Returns the selected rows among the first `length` as a [Mask], the form Rust's filter
    /// evaluates to: one walk sets a bit per selected row.
    ///
    /// @param length the chunk's row count
    /// @return the mask of the selected rows
    default Mask toMask(int length) {
        long[] words = new long[(length + 63) >>> 6];
        for (long row = nextSelected(0); row >= 0 && row < length; row = nextSelected(row + 1)) {
            words[(int) (row >>> 6)] |= 1L << row;
        }
        return Mask.fromBitmap(words, length);
    }
}
