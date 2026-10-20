package io.github.dfa1.vortex.parquet;

import dev.hardwood.writer.WriterConfig;
import io.github.dfa1.vortex.core.model.ColumnName;

import java.util.List;

/// Options controlling Vortex → Parquet export.
public final class ExportOptions {
    private final List<ColumnName> columns;
    private final ProgressListener progressListener;
    private final WriterConfig writerConfig;

    private ExportOptions(List<ColumnName> columns, ProgressListener progressListener, WriterConfig writerConfig) {
        this.columns = columns;
        this.progressListener = progressListener;
        this.writerConfig = writerConfig;
    }

    /// The top-level columns to export, in order.
    ///
    /// @return the projected columns; empty means all columns
    public List<ColumnName> columns() {
        return columns;
    }

    /// The progress callback.
    ///
    /// @return the listener, or `null`
    public ProgressListener progressListener() {
        return progressListener;
    }

    /// The Parquet writer configuration.
    ///
    /// @return the writer config
    public WriterConfig writerConfig() {
        return writerConfig;
    }

    /// Returns the default options: every column, no progress listener, Hardwood's default writer
    /// config.
    ///
    /// @return the default options
    public static ExportOptions defaults() {
        return new ExportOptions(List.of(), null, WriterConfig.defaults());
    }

    /// Restrict export to specific top-level columns, in the given order. Empty list = all columns.
    ///
    /// @param cols the columns to export
    /// @return a copy exporting only `cols`
    public ExportOptions withColumns(List<ColumnName> cols) {
        return new ExportOptions(List.copyOf(cols), progressListener, writerConfig);
    }

    /// Reports whether the export reads a subset of the columns.
    ///
    /// @return `true` if a column projection is set
    public boolean hasProjection() {
        return !columns.isEmpty();
    }

    /// Returns a copy that reports progress to the given listener.
    ///
    /// @param listener called after each chunk is written
    /// @return a copy with the given listener
    public ExportOptions withProgressListener(ProgressListener listener) {
        return new ExportOptions(columns, listener, writerConfig);
    }

    /// Returns a copy that writes Parquet with the given config.
    ///
    /// @param config the Parquet writer settings
    /// @return a copy with the given writer config
    public ExportOptions withWriterConfig(WriterConfig config) {
        return new ExportOptions(columns, progressListener, config);
    }
}
