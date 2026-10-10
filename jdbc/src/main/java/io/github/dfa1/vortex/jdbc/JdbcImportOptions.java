package io.github.dfa1.vortex.jdbc;

import io.github.dfa1.vortex.writer.WriteOptions;

/// Options controlling JDBC → Vortex import.
public final class JdbcImportOptions {
    private final int fetchSize;
    private final int chunkSize;
    private final WriteOptions writeOptions;
    private final ProgressListener progressListener;

    private JdbcImportOptions(int fetchSize, int chunkSize, WriteOptions writeOptions, ProgressListener progressListener) {
        this.fetchSize = fetchSize;
        this.chunkSize = chunkSize;
        this.writeOptions = writeOptions;
        this.progressListener = progressListener;
    }

    /// The number of rows the JDBC driver fetches per round-trip.
    ///
    /// @return the fetch size
    public int fetchSize() {
        return fetchSize;
    }

    /// The number of rows per Vortex chunk written to disk.
    ///
    /// @return the chunk size
    public int chunkSize() {
        return chunkSize;
    }

    /// The encoding and compression settings passed to [io.github.dfa1.vortex.writer.VortexWriter].
    ///
    /// @return the write options
    public WriteOptions writeOptions() {
        return writeOptions;
    }

    /// The callback invoked after each full chunk is written.
    ///
    /// @return the listener, or `null` when progress reporting is disabled
    public ProgressListener progressListener() {
        return progressListener;
    }

    /// Returns a default configuration: fetch size 10 000, chunk size 65 536, cascading compression at level 3, no progress listener.
    ///
    /// @return default import options
    public static JdbcImportOptions defaults() {
        return new JdbcImportOptions(10_000, 65_536, WriteOptions.cascading(3), null);
    }

    /// Returns a copy with the JDBC fetch size set to `size`.
    ///
    /// @param size number of rows fetched per driver round-trip
    /// @return updated options
    public JdbcImportOptions withFetchSize(int size) {
        return new JdbcImportOptions(size, chunkSize, writeOptions, progressListener);
    }

    /// Returns a copy with the Vortex chunk size set to `size`.
    ///
    /// @param size number of rows per written chunk
    /// @return updated options
    public JdbcImportOptions withChunkSize(int size) {
        return new JdbcImportOptions(fetchSize, size, writeOptions, progressListener);
    }

    /// Returns a copy with the given write options.
    ///
    /// @param opts encoding and compression settings
    /// @return updated options
    public JdbcImportOptions withWriteOptions(WriteOptions opts) {
        return new JdbcImportOptions(fetchSize, chunkSize, opts, progressListener);
    }

    /// Returns a copy with the given progress listener.
    ///
    /// @param listener callback invoked after each full chunk; pass `null` to disable
    /// @return updated options
    public JdbcImportOptions withProgressListener(ProgressListener listener) {
        return new JdbcImportOptions(fetchSize, chunkSize, writeOptions, listener);
    }
}
