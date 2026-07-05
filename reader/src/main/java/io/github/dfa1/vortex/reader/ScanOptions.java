package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.ColumnName;

import java.util.Arrays;
import java.util.List;

/// Options controlling a file scan.
///
/// Empty `columns` = read all columns.
/// Null `rowFilter` = no zone-map pruning.
///
/// Projection names are supplied as strings at the API boundary and validated into
/// [ColumnName] on construction, so a blank or control-character projection name fails fast
/// rather than silently matching nothing.
///
/// @param columns   projected column names; empty means all columns
/// @param rowFilter zone-map pruning filter, or `null` for none
/// @param limit     row limit, or [#NO_LIMIT]
public record ScanOptions(
        List<ColumnName> columns,
        RowFilter rowFilter,
        long limit
) {
    /// Sentinel limit meaning "no row limit".
    public static final long NO_LIMIT = Long.MAX_VALUE;

    /// Scans every column with no filter or limit.
    ///
    /// @return options selecting all columns
    public static ScanOptions all() {
        return new ScanOptions(List.of(), null, NO_LIMIT);
    }

    /// Projects the named columns.
    ///
    /// @param names column names to project
    /// @return options projecting `names`
    public static ScanOptions columns(String... names) {
        return new ScanOptions(toColumnNames(names), null, NO_LIMIT);
    }

    /// Scans every column, capped at `limit` rows.
    ///
    /// @param limit maximum rows to read
    /// @return options with the given row limit
    public static ScanOptions limit(long limit) {
        return new ScanOptions(List.of(), null, limit);
    }

    /// Returns a copy projecting the named columns.
    ///
    /// @param names column names to project
    /// @return a copy with `names` projected
    public ScanOptions withColumns(String... names) {
        return new ScanOptions(toColumnNames(names), rowFilter, limit);
    }

    /// Returns a copy with the given row limit.
    ///
    /// @param limit maximum rows to read
    /// @return a copy with the given limit
    public ScanOptions withLimit(long limit) {
        return new ScanOptions(columns, rowFilter, limit);
    }

    /// Returns a copy with the given zone-map filter.
    ///
    /// @param filter the row filter, or `null` for none
    /// @return a copy with the given filter
    public ScanOptions withFilter(RowFilter filter) {
        return new ScanOptions(columns, filter, limit);
    }

    /// @return `true` if a column projection is set
    public boolean hasProjection() {
        return !columns.isEmpty();
    }

    /// @return `true` if a zone-map filter is set
    public boolean hasFilter() {
        return rowFilter != null;
    }

    /// @return `true` if a row limit is set
    public boolean hasLimit() {
        return limit != NO_LIMIT;
    }

    private static List<ColumnName> toColumnNames(String... names) {
        return Arrays.stream(names).map(ColumnName::of).toList();
    }
}
