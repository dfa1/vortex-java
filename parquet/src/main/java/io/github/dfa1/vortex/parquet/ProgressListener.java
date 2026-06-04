package io.github.dfa1.vortex.parquet;

/// Callback invoked after each chunk is written during Parquet import.
@FunctionalInterface
public interface ProgressListener {
    void onProgress(long rowsDone, long rowsTotal);
}
