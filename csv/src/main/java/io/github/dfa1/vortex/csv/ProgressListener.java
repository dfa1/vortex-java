package io.github.dfa1.vortex.csv;

/// Callback invoked after each chunk is written during CSV import.
@FunctionalInterface
public interface ProgressListener {
    void onProgress(long rowsDone, long rowsTotal);
}
