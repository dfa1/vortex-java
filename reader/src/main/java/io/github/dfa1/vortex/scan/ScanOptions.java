package io.github.dfa1.vortex.scan;

import java.util.List;

/// Options controlling a file scan.
///
/// Empty `columns` = read all columns.
/// Null `rowFilter` = no zone-map pruning.
public record ScanOptions(
        List<String> columns,
        RowFilter rowFilter,
        long limit
) {
    public static final long NO_LIMIT = Long.MAX_VALUE;

    public static ScanOptions all() {
        return new ScanOptions(List.of(), null, NO_LIMIT);
    }

    public static ScanOptions columns(String... names) {
        return new ScanOptions(List.of(names), null, NO_LIMIT);
    }

    public static ScanOptions limit(long limit) {
        return new ScanOptions(List.of(), null, limit);
    }

    public ScanOptions withColumns(String... names) {
        return new ScanOptions(List.of(names), rowFilter, limit);
    }

    public ScanOptions withLimit(long limit) {
        return new ScanOptions(columns, rowFilter, limit);
    }

    public ScanOptions withFilter(RowFilter filter) {
        return new ScanOptions(columns, filter, limit);
    }

    public boolean hasProjection() {
        return !columns.isEmpty();
    }

    public boolean hasFilter() {
        return rowFilter != null;
    }

    public boolean hasLimit() {
        return limit != NO_LIMIT;
    }
}
