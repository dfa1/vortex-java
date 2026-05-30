package io.github.dfa1.vortex.csv;

/// Options controlling Vortex → CSV export.
public record ExportOptions(
        char delimiter,
        boolean writeHeader
) {
    public static ExportOptions defaults() {
        return new ExportOptions(',', true);
    }
}
