package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.scan.ScanResult;

/// Row-level predicate evaluated against decoded chunk data.
/// Used in conjunction with zone-map pruning: zone-maps skip whole chunks,
/// this predicate filters individual rows within surviving chunks.
@FunctionalInterface
public interface RowPredicate {
    boolean test(ScanResult chunk, long rowIndex);

    static RowPredicate all() {
        return (_, _) -> true;
    }
}
