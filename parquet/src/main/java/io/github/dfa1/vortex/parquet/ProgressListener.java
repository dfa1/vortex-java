package io.github.dfa1.vortex.parquet;

/// Callback invoked after each chunk is written during Parquet import.
@FunctionalInterface
public interface ProgressListener {

    /// Called after each chunk is written.
    ///
    /// @param rowsDone  rows written so far
    /// @param rowsTotal rows in the whole file
    void onProgress(long rowsDone, long rowsTotal);
}
