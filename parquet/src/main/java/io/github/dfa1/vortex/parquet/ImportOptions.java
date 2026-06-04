package io.github.dfa1.vortex.parquet;

import io.github.dfa1.vortex.writer.WriteOptions;

import java.util.List;

/// Options controlling Parquet → Vortex import.
public record ImportOptions(
        int chunkSize,
        List<String> columns,
        ProgressListener progressListener,
        WriteOptions writeOptions
) {
    public static ImportOptions defaults() {
        return new ImportOptions(65_536, List.of(), null, WriteOptions.cascading(3));
    }

    /// Restrict import to specific columns. Empty list = all columns.
    public ImportOptions withColumns(List<String> cols) {
        return new ImportOptions(chunkSize, List.copyOf(cols), progressListener, writeOptions);
    }

    public boolean hasProjection() {
        return !columns.isEmpty();
    }

    public ImportOptions withProgressListener(ProgressListener listener) {
        return new ImportOptions(chunkSize, columns, listener, writeOptions);
    }

    public ImportOptions withWriteOptions(WriteOptions options) {
        return new ImportOptions(chunkSize, columns, progressListener, options);
    }

    public ImportOptions withChunkSize(int size) {
        return new ImportOptions(size, columns, progressListener, writeOptions);
    }
}
