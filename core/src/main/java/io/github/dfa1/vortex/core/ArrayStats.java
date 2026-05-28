package io.github.dfa1.vortex.core;

/// Per-array statistics embedded in the encoding tree.
public record ArrayStats(
    Object  min,
    Object  max,
    Long    trueCount,
    Long    nullCount,
    Boolean isSorted,
    Boolean isStrictSorted
) {
    public static ArrayStats empty() {
        return new ArrayStats(null, null, null, null, null, null);
    }
}
