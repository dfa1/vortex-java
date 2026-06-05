package io.github.dfa1.vortex.jdbc;

import io.github.dfa1.vortex.writer.WriteOptions;

/// Options controlling JDBC → Vortex import.
///
/// @param fetchSize         number of rows the JDBC driver fetches per round-trip
/// @param chunkSize         number of rows per Vortex chunk written to disk
/// @param writeOptions      encoding and compression settings passed to {@link io.github.dfa1.vortex.writer.VortexWriter}
/// @param progressListener  optional callback invoked after each full chunk is written; {@code null} disables progress reporting
public record JdbcImportOptions(
        int fetchSize,
        int chunkSize,
        WriteOptions writeOptions,
        ProgressListener progressListener
) {
    /// Returns a default configuration: fetch size 10 000, chunk size 65 536, cascading compression at level 3, no progress listener.
    ///
    /// @return default import options
    public static JdbcImportOptions defaults() {
        return new JdbcImportOptions(10_000, 65_536, WriteOptions.cascading(3), null);
    }

    /// Returns a copy with the JDBC fetch size set to {@code size}.
    ///
    /// @param size number of rows fetched per driver round-trip
    /// @return updated options
    public JdbcImportOptions withFetchSize(int size) {
        return new JdbcImportOptions(size, chunkSize, writeOptions, progressListener);
    }

    /// Returns a copy with the Vortex chunk size set to {@code size}.
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
    /// @param listener callback invoked after each full chunk; pass {@code null} to disable
    /// @return updated options
    public JdbcImportOptions withProgressListener(ProgressListener listener) {
        return new JdbcImportOptions(fetchSize, chunkSize, writeOptions, listener);
    }
}
