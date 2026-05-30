package io.github.dfa1.vortex.csv;

import java.util.List;

/// Options controlling Vortex → CSV export.
public record ExportOptions(
        char delimiter,
        boolean writeHeader,
        List<String> columns
) {
    public static ExportOptions defaults() {
        return new ExportOptions(',', true, List.of());
    }

    /// Restrict output to specific columns (projection). Empty list = all columns.
    public ExportOptions withColumns(List<String> cols) {
        return new ExportOptions(delimiter, writeHeader, List.copyOf(cols));
    }

    public boolean hasProjection() {
        return !columns.isEmpty();
    }
}
