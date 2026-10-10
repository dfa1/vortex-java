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

    public static ExportOptions defaults() {
        return new ExportOptions(List.of(), null, WriterConfig.defaults());
    }

    /// Restrict export to specific top-level columns, in the given order. Empty list = all columns.
    public ExportOptions withColumns(List<ColumnName> cols) {
        return new ExportOptions(List.copyOf(cols), progressListener, writerConfig);
    }

    public boolean hasProjection() {
        return !columns.isEmpty();
    }

    public ExportOptions withProgressListener(ProgressListener listener) {
        return new ExportOptions(columns, listener, writerConfig);
    }

    public ExportOptions withWriterConfig(WriterConfig config) {
        return new ExportOptions(columns, progressListener, config);
    }
}
