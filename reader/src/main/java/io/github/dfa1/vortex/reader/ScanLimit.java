package io.github.dfa1.vortex.reader;

/// The most rows a scan returns, as Rust's `ScanBuilder::with_limit`.
///
/// @param rows the row cap, zero or more
public record ScanLimit(long rows) {

    /// Validates the cap.
    ///
    /// @throws IllegalArgumentException if `rows` is negative
    public ScanLimit {
        if (rows < 0) {
            throw new IllegalArgumentException("scan limit must not be negative: " + rows);
        }
    }

    /// A limit of `rows` rows.
    ///
    /// @param rows the row cap, zero or more
    /// @return the limit
    /// @throws IllegalArgumentException if `rows` is negative
    public static ScanLimit of(long rows) {
        return new ScanLimit(rows);
    }
}
