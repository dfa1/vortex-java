package io.github.dfa1.vortex.csv;

import java.util.List;

/// Options controlling Vortex → CSV export.
public record ExportOptions(
        char delimiter,
        boolean writeHeader,
        List<String> columns,
        ProgressListener progressListener
) {
    public static ExportOptions defaults() {
        return new ExportOptions(',', true, List.of(), null);
    }

    /// Restrict output to specific columns (projection). Empty list = all columns.
    public ExportOptions withColumns(List<String> cols) {
        return new ExportOptions(delimiter, writeHeader, List.copyOf(cols), progressListener);
    }

    /// Attach a progress callback invoked periodically with `(rowsDone, rowsTotal)`.
    public ExportOptions withProgressListener(ProgressListener listener) {
        return new ExportOptions(delimiter, writeHeader, columns, listener);
    }

    public boolean hasProjection() {
        return !columns.isEmpty();
    }
}
