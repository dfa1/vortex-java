package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.ColumnName;

import java.util.List;

/// Options controlling a file scan: an immutable configuration built from [#all()],
/// [#columns(ColumnName...)] or [#limit(long)] and adjusted with the `withXxx` methods, each
/// returning a copy.
///
/// Empty `columns` = read all columns.
/// Null `rowFilter` = no zone-map pruning.
///
/// Projection names are validated [ColumnName]s, so a control-character name fails at the
/// call site rather than silently matching nothing.
///
/// A plain class rather than a record, like `WriteOptions`: it is configuration, not a value.
public final class ScanOptions {
    /// Sentinel limit meaning "no row limit".
    public static final long NO_LIMIT = Long.MAX_VALUE;

    private final List<ColumnName> columns;
    private final RowFilter rowFilter;
    private final long limit;

    private ScanOptions(List<ColumnName> columns, RowFilter rowFilter, long limit) {
        this.columns = List.copyOf(columns);
        this.rowFilter = rowFilter;
        this.limit = limit;
    }

    /// The projected column names.
    ///
    /// @return the projected columns; empty means all columns
    public List<ColumnName> columns() {
        return columns;
    }

    /// The zone-map pruning filter.
    ///
    /// @return the row filter, or `null` for none
    public RowFilter rowFilter() {
        return rowFilter;
    }

    /// The row limit.
    ///
    /// @return the maximum rows to read, or [#NO_LIMIT]
    public long limit() {
        return limit;
    }

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
    public static ScanOptions columns(ColumnName... names) {
        return new ScanOptions(List.of(names), null, NO_LIMIT);
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
    public ScanOptions withColumns(ColumnName... names) {
        return new ScanOptions(List.of(names), rowFilter, limit);
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
}
