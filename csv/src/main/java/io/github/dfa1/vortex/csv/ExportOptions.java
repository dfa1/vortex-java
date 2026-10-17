package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.reader.ReadRegistry;

import java.util.List;

/// Options controlling Vortex → CSV export.
public final class ExportOptions {
    private final char delimiter;
    private final boolean header;
    private final List<ColumnName> columns;
    private final ProgressListener progressListener;
    private final ReadRegistry readRegistry;

    private ExportOptions(char delimiter, boolean header, List<ColumnName> columns, ProgressListener progressListener,
            ReadRegistry readRegistry) {
        this.delimiter = delimiter;
        this.header = header;
        this.columns = columns;
        this.progressListener = progressListener;
        this.readRegistry = readRegistry;
    }

    /// The field delimiter.
    ///
    /// @return the delimiter
    public char delimiter() {
        return delimiter;
    }

    /// Whether a header row with column names is written.
    ///
    /// @return `true` if the header is written
    public boolean header() {
        return header;
    }

    /// The columns to include in output, in order.
    ///
    /// @return the projected columns; empty means all columns
    public List<ColumnName> columns() {
        return columns;
    }

    /// The callback invoked periodically with `(rowsDone, rowsTotal)`.
    ///
    /// @return the listener, or `null`
    public ProgressListener progressListener() {
        return progressListener;
    }

    /// The registry the Vortex file is opened with.
    ///
    /// @return the read registry
    public ReadRegistry readRegistry() {
        return readRegistry;
    }

    /// Default options: comma delimiter, header row written, no projection, no progress listener,
    /// the built-in [ReadRegistry].
    ///
    /// @return the default options
    public static ExportOptions defaults() {
        return new ExportOptions(',', true, List.of(), null, ReadRegistry.loadAll());
    }

    /// Override whether a header row with column names is written.
    ///
    /// @param header `true` to write the header row
    /// @return a copy of this options with the header flag applied
    public ExportOptions withHeader(boolean header) {
        return new ExportOptions(delimiter, header, columns, progressListener, readRegistry);
    }

    /// Restrict output to specific columns (projection). Empty list = all columns.
    ///
    /// @param cols the column names to include, in output order
    /// @return a copy of this options with the projection applied
    public ExportOptions withColumns(List<ColumnName> cols) {
        return new ExportOptions(delimiter, header, List.copyOf(cols), progressListener, readRegistry);
    }

    /// Attach a progress callback invoked periodically with `(rowsDone, rowsTotal)`.
    ///
    /// @param listener the progress callback
    /// @return a copy of this options with the listener attached
    public ExportOptions withProgressListener(ProgressListener listener) {
        return new ExportOptions(delimiter, header, columns, listener, readRegistry);
    }

    /// Open the Vortex file with `registry`, e.g. one built with
    /// [ReadRegistry.Builder#allowUnknown()] to export columns of an unregistered extension type as
    /// their storage.
    ///
    /// @param registry the read registry
    /// @return a copy of this options with the registry applied
    public ExportOptions withReadRegistry(ReadRegistry registry) {
        return new ExportOptions(delimiter, header, columns, progressListener, java.util.Objects.requireNonNull(registry));
    }

    /// Whether a column projection has been applied via [#withColumns(List)].
    ///
    /// @return true if this options restricts output to a subset of columns
    public boolean hasProjection() {
        return !columns.isEmpty();
    }
}
