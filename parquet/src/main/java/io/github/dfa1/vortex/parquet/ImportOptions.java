package io.github.dfa1.vortex.parquet;

import io.github.dfa1.vortex.writer.WriteOptions;

import java.util.List;

/// Options controlling Parquet → Vortex import.
public final class ImportOptions {
    private static final long DEFAULT_MAX_CHUNK_BYTES = 64L << 20;

    private final int chunkSize;
    private final long maxChunkBytes;
    private final List<String> columns;
    private final ProgressListener progressListener;
    private final WriteOptions writeOptions;

    private ImportOptions(int chunkSize, long maxChunkBytes, List<String> columns, ProgressListener progressListener, WriteOptions writeOptions) {
        this.chunkSize = chunkSize;
        this.maxChunkBytes = maxChunkBytes;
        this.columns = columns;
        this.progressListener = progressListener;
        this.writeOptions = writeOptions;
    }

    /// The most physical rows per Vortex chunk; a chunk closes sooner once it holds [#maxChunkBytes()].
    ///
    /// @return the chunk size
    public int chunkSize() {
        return chunkSize;
    }

    /// The text and binary size after which a chunk is closed even if it has fewer than
    /// [#chunkSize()] rows, bounding the heap a wide-string file needs. A string counts its
    /// UTF-16 chars, not its UTF-8 bytes, and the check runs after each row, so a chunk can
    /// exceed the bound by one row.
    ///
    /// @return the byte bound of a chunk
    public long maxChunkBytes() {
        return maxChunkBytes;
    }

    /// The columns to import.
    ///
    /// @return the projected columns; empty means all columns
    public List<String> columns() {
        return columns;
    }

    /// The progress callback.
    ///
    /// @return the listener, or `null`
    public ProgressListener progressListener() {
        return progressListener;
    }

    /// The writer options used to encode the resulting Vortex file.
    ///
    /// @return the write options
    public WriteOptions writeOptions() {
        return writeOptions;
    }

    /// Returns the default options: every column, 65536 rows or 64 MiB of text and binary per chunk, no progress listener,
    /// cascading compression.
    ///
    /// @return the default options
    public static ImportOptions defaults() {
        // 65536 physical rows per chunk, matching WriteOptions' default and the Rust reference's
        // 32k-64k granularity. This was briefly raised to 131072 for a size win (962520ac), but the
        // FSST + global-dict work (#299) reversed that: finer chunks now compress better on
        // real-world data (nyc-311 -66 MB, taxi -0.14 MB) as their per-chunk cost competition and
        // FSST training adapt more locally. 32768 is worse than 65536 — per-chunk overhead outweighs
        // the finer adaptation below 64k.
        return new ImportOptions(65_536, DEFAULT_MAX_CHUNK_BYTES, List.of(), null, WriteOptions.cascading(3));
    }

    /// Restrict import to specific columns. Empty list = all columns.
    ///
    /// @param cols the columns to import
    /// @return a copy importing only `cols`
    public ImportOptions withColumns(List<String> cols) {
        return new ImportOptions(chunkSize, maxChunkBytes, List.copyOf(cols), progressListener, writeOptions);
    }

    /// Reports whether the import reads a subset of the columns.
    ///
    /// @return `true` if a column projection is set
    public boolean hasProjection() {
        return !columns.isEmpty();
    }

    /// Returns a copy that reports progress to the given listener.
    ///
    /// @param listener called after each chunk is written
    /// @return a copy with the given listener
    public ImportOptions withProgressListener(ProgressListener listener) {
        return new ImportOptions(chunkSize, maxChunkBytes, columns, listener, writeOptions);
    }

    /// Returns a copy that writes the Vortex file with the given options.
    ///
    /// @param options the Vortex write options
    /// @return a copy with the given write options
    public ImportOptions withWriteOptions(WriteOptions options) {
        return new ImportOptions(chunkSize, maxChunkBytes, columns, progressListener, options);
    }

    /// Returns a copy that writes chunks of at most the given number of rows.
    ///
    /// @param size rows per chunk
    /// @return a copy with the given chunk size
    public ImportOptions withChunkSize(int size) {
        return new ImportOptions(size, maxChunkBytes, columns, progressListener, writeOptions);
    }

    /// Returns a copy that closes a chunk once its text and binary values hold the given number
    /// of bytes, whatever its row count: an importer-side input bound, the file's chunks are not
    /// otherwise affected.
    ///
    /// @param bytes the byte bound of a chunk, at least 1
    /// @return a copy with the given byte bound
    public ImportOptions withMaxChunkBytes(long bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("maxChunkBytes must be at least 1: " + bytes);
        }
        return new ImportOptions(chunkSize, bytes, columns, progressListener, writeOptions);
    }
}
