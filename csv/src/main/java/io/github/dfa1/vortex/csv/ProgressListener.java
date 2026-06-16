package io.github.dfa1.vortex.csv;

/// Callback invoked during CSV import or export to report row-level progress.
@FunctionalInterface
public interface ProgressListener {
    void onProgress(long rowsDone, long rowsTotal);
}
