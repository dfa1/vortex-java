package io.github.dfa1.vortex.core.model;

/// Per-array statistics embedded in the encoding tree: one component per field of the wire
/// `ArrayStats` table (Rust's `Stat` enum). A bool column's true count is its `sum`, as in Rust.
///
/// `min`/`max` may be inexact (Rust's `Precision::Inexact`): still a valid bound, so pruning on a
/// range may use it, but not the true extreme (Rust's flat writer truncates a string bound longer
/// than 64 bytes). Anything that reports the value, or needs `min == max`, requires
/// `minExact`/`maxExact`.
///
/// The counts are `u64` on the wire, read as a signed `Long`.
///
/// @param min                     minimum value in the array, or `null` if unknown
/// @param minExact                `true` if `min` is the exact minimum, not only a lower bound
/// @param max                     maximum value in the array, or `null` if unknown
/// @param maxExact                `true` if `max` is the exact maximum, not only an upper bound
/// @param sum                     sum of values (`Long` for integer and bool columns, `Double` for floats), or `null` if unknown
/// @param isSorted                `true` if the values are sorted ascending with nulls first (Rust orders null lowest), or `null` if unknown
/// @param isStrictSorted          `true` if also no two neighbors are equal, two nulls included, or `null` if unknown
/// @param isConstant              `true` if every value is null, or none is and all are equal, or `null` if unknown
/// @param nullCount               number of null values, or `null` if unknown
/// @param uncompressedSizeInBytes the array's uncompressed size in bytes, or `null` if unknown
/// @param nanCount                number of `NaN` values in a float column (`min` and `max` skip them), or `null` if unknown
public record ArrayStats(
        Object min,
        boolean minExact,
        Object max,
        boolean maxExact,
        Object sum,
        Boolean isSorted,
        Boolean isStrictSorted,
        Boolean isConstant,
        Long nullCount,
        Long uncompressedSizeInBytes,
        Long nanCount) {
    private static final ArrayStats EMPTY =
            new ArrayStats(null, false, null, false, null, null, null, null, null, null, null);

    /// Returns an empty stats instance with all fields set to `null`.
    ///
    /// @return an empty [ArrayStats] with no known statistics
    public static ArrayStats empty() {
        return EMPTY;
    }
}
