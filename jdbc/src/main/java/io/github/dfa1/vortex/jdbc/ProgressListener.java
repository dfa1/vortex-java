package io.github.dfa1.vortex.jdbc;

/// Callback invoked after each chunk is written during JDBC import.
/// `rowsTotal` is `-1` when the total row count is unknown.
@FunctionalInterface
public interface ProgressListener {
    /// Called after each full chunk is flushed to disk.
    ///
    /// @param rowsDone  cumulative number of rows written so far
    /// @param rowsTotal total row count, or `-1` if unknown
    void onProgress(long rowsDone, long rowsTotal);
}
