package io.github.dfa1.vortex.csv;

import java.util.List;

/// Options controlling Vortex → CSV export.
///
/// @param delimiter the field delimiter
/// @param writeHeader whether to write a header row with column names
/// @param columns the columns to include in output, in order; empty = all columns
/// @param progressListener callback invoked periodically with `(rowsDone, rowsTotal)`, or null
public record ExportOptions(
        char delimiter,
        boolean writeHeader,
        List<String> columns,
        ProgressListener progressListener
) {
    /// Default options: comma delimiter, header row written, no projection, no progress listener.
    ///
    /// @return the default options
    public static ExportOptions defaults() {
        return new ExportOptions(',', true, List.of(), null);
    }

    /// Restrict output to specific columns (projection). Empty list = all columns.
    ///
    /// @param cols the column names to include, in output order
    /// @return a copy of this options with the projection applied
    public ExportOptions withColumns(List<String> cols) {
        return new ExportOptions(delimiter, writeHeader, List.copyOf(cols), progressListener);
    }

    /// Attach a progress callback invoked periodically with `(rowsDone, rowsTotal)`.
    ///
    /// @param listener the progress callback
    /// @return a copy of this options with the listener attached
    public ExportOptions withProgressListener(ProgressListener listener) {
        return new ExportOptions(delimiter, writeHeader, columns, listener);
    }

    /// Whether a column projection has been applied via [#withColumns(List)].
    ///
    /// @return true if this options restricts output to a subset of columns
    public boolean hasProjection() {
        return !columns.isEmpty();
    }
}
