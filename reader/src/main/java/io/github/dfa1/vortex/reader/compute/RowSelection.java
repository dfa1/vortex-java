package io.github.dfa1.vortex.reader.compute;

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
}
