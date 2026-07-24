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
        // 65536 physical rows per chunk, matching WriteOptions' default and the Rust reference's
        // 32k-64k granularity. This was briefly raised to 131072 for a size win (962520ac), but the
        // FSST + global-dict work (#299) reversed that: finer chunks now compress better on
        // real-world data (nyc-311 -66 MB, taxi -0.14 MB) as their per-chunk cost competition and
        // FSST training adapt more locally. 32768 is worse than 65536 — per-chunk overhead outweighs
        // the finer adaptation below 64k.
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
