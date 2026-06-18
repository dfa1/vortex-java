package io.github.dfa1.vortex.csv;

/// Callback invoked during CSV import or export to report row-level progress.
@FunctionalInterface
public interface ProgressListener {

    /// Called after each chunk is processed.
    ///
    /// @param rowsDone  number of data rows processed so far
    /// @param rowsTotal total row count, or `-1` if the total is not known (streaming import)
    void onProgress(long rowsDone, long rowsTotal);
}
