package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.reader.Chunk;

/// Row-level predicate evaluated against decoded chunk data.
/// Used in conjunction with zone-map pruning: zone-maps skip whole chunks,
/// this predicate filters individual rows within surviving chunks.
@FunctionalInterface
public interface RowPredicate {
    /// Returns a predicate that accepts every row.
    ///
    /// @return predicate that always returns {@code true}
    static RowPredicate all() {
        return (_, _) -> true;
    }

    /// Tests whether a row should be exported.
    ///
    /// @param chunk    decoded chunk containing the row
    /// @param rowIndex row index within {@code chunk}
    /// @return {@code true} if the row should be exported
    boolean test(Chunk chunk, long rowIndex);
}
