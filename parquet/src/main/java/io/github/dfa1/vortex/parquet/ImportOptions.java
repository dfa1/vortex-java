package io.github.dfa1.vortex.parquet;

import io.github.dfa1.vortex.writer.WriteOptions;

/// Options controlling Parquet → Vortex import.
public record ImportOptions(
        int chunkSize,
        ProgressListener progressListener,
        WriteOptions writeOptions
) {
    public static ImportOptions defaults() {
        return new ImportOptions(65_536, null, WriteOptions.cascading(3));
    }

    public ImportOptions withProgressListener(ProgressListener listener) {
        return new ImportOptions(chunkSize, listener, writeOptions);
    }

    public ImportOptions withWriteOptions(WriteOptions options) {
        return new ImportOptions(chunkSize, progressListener, options);
    }

    public ImportOptions withChunkSize(int size) {
        return new ImportOptions(size, progressListener, writeOptions);
    }
}
