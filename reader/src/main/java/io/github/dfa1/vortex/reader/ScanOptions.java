package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.ColumnName;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Options controlling a file scan: an immutable configuration built from [#all()] or
/// [#columns(ColumnName...)] and adjusted with the `withXxx` methods, each returning a copy.
///
/// Empty `columns` = read all columns. No filter = no zone-map pruning; no limit = every row. The
/// filter and the limit are optional, as in Rust's `ScanBuilder`.
///
/// Projection names are validated [ColumnName]s, so a control-character name fails at the
/// call site rather than silently matching nothing.
///
/// A plain class rather than a record, like `WriteOptions`: it is configuration, not a value.
public final class ScanOptions {

    private final List<ColumnName> columns;
    private final ScanFilter filter;
    private final ScanLimit limit;

    private ScanOptions(List<ColumnName> columns, ScanFilter filter, ScanLimit limit) {
        this.columns = List.copyOf(columns);
        this.filter = filter;
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
    /// @return the filter, or empty for none
    public Optional<ScanFilter> filter() {
        return Optional.ofNullable(filter);
    }

    /// The most rows the scan returns.
    ///
    /// @return the limit, or empty for every row
    public Optional<ScanLimit> limit() {
        return Optional.ofNullable(limit);
    }

    /// Scans every column with no filter or limit.
    ///
    /// @return options selecting all columns
    public static ScanOptions all() {
        return new ScanOptions(List.of(), null, null);
    }

    /// Projects the named columns.
    ///
    /// @param names column names to project
    /// @return options projecting `names`
    public static ScanOptions columns(ColumnName... names) {
        return new ScanOptions(List.of(names), null, null);
    }

    /// Returns a copy projecting the named columns.
    ///
    /// @param names column names to project
    /// @return a copy with `names` projected
    public ScanOptions withColumns(ColumnName... names) {
        return new ScanOptions(List.of(names), filter, limit);
    }

    /// Returns a copy with the given row limit.
    ///
    /// @param limit the most rows to return
    /// @return a copy with the given limit
    public ScanOptions withLimit(ScanLimit limit) {
        return new ScanOptions(columns, filter, Objects.requireNonNull(limit, "limit"));
    }

    /// Returns a copy with the given zone-map filter.
    ///
    /// @param filter the filter
    /// @return a copy with the given filter
    public ScanOptions withFilter(ScanFilter filter) {
        return new ScanOptions(columns, Objects.requireNonNull(filter, "filter"), limit);
    }

    /// Reports whether the scan reads a subset of the columns.
    ///
    /// @return `true` if a column projection is set
    public boolean hasProjection() {
        return !columns.isEmpty();
    }
}
