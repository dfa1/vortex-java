package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.reader.compute.Predicate;

import java.util.List;
import java.util.Objects;

/// A column-bound predicate tree for zone-map pruning and row selection. A [RowFilter.Column] binds
/// one column to a value-test [Predicate]; a [RowFilter.And] conjoins several. The same [Predicate]
/// vocabulary the compute kernels evaluate against a decoded array (ADR 0013 §4) is here compiled
/// against per-chunk statistics — min/max for the comparisons, null count for the null tests — so a
/// chunk where no row can satisfy the filter is skipped entirely (ADR 0013 §5).
///
/// Build filters through the static factories ([#eq(String, Object)], [#gt(String, Comparable)],
/// [#isNull(String)], …); each wraps the column name and the matching [Predicate]. Conjoin with
/// [#and(RowFilter...)] or the [#and(RowFilter)] instance method.
public sealed interface RowFilter permits RowFilter.Column, RowFilter.And {

    /// Conjoins the given filters into a single [RowFilter.And].
    ///
    /// @param filters the filters to conjoin
    /// @return an `And` over `filters`
    static RowFilter and(RowFilter... filters) {
        return new And(List.of(filters));
    }

    /// Builds a filter selecting rows where `col` equals `val`.
    ///
    /// @param col the column name
    /// @param val the value to compare against
    /// @return a `Column` bound to a [Predicate.Eq]
    static RowFilter eq(String col, Object val) {
        return new Column(ColumnName.of(col), new Predicate.Eq(val));
    }

    /// Builds a filter selecting rows where `col` differs from `val`.
    ///
    /// @param col the column name
    /// @param val the value to compare against
    /// @return a `Column` bound to a [Predicate.Neq]
    static RowFilter neq(String col, Object val) {
        return new Column(ColumnName.of(col), new Predicate.Neq(val));
    }

    /// Builds a filter selecting rows where `col` is strictly greater than `val`.
    ///
    /// @param col the column name
    /// @param val the exclusive lower bound
    /// @return a `Column` bound to a [Predicate.Gt]
    static RowFilter gt(String col, Comparable<?> val) {
        return new Column(ColumnName.of(col), new Predicate.Gt(val));
    }

    /// Builds a filter selecting rows where `col` is greater than or equal to `val`.
    ///
    /// @param col the column name
    /// @param val the inclusive lower bound
    /// @return a `Column` bound to a [Predicate.Gte]
    static RowFilter gte(String col, Comparable<?> val) {
        return new Column(ColumnName.of(col), new Predicate.Gte(val));
    }

    /// Builds a filter selecting rows where `col` is strictly less than `val`.
    ///
    /// @param col the column name
    /// @param val the exclusive upper bound
    /// @return a `Column` bound to a [Predicate.Lt]
    static RowFilter lt(String col, Comparable<?> val) {
        return new Column(ColumnName.of(col), new Predicate.Lt(val));
    }

    /// Builds a filter selecting rows where `col` is less than or equal to `val`.
    ///
    /// @param col the column name
    /// @param val the inclusive upper bound
    /// @return a `Column` bound to a [Predicate.Lte]
    static RowFilter lte(String col, Comparable<?> val) {
        return new Column(ColumnName.of(col), new Predicate.Lte(val));
    }

    /// Builds a filter selecting rows where `col` is null.
    ///
    /// @param col the column name
    /// @return a `Column` bound to a [Predicate.IsNull]
    static RowFilter isNull(String col) {
        return new Column(ColumnName.of(col), new Predicate.IsNull());
    }

    /// Builds a filter selecting rows where `col` is not null.
    ///
    /// @param col the column name
    /// @return a `Column` bound to a [Predicate.IsNotNull]
    static RowFilter isNotNull(String col) {
        return new Column(ColumnName.of(col), new Predicate.IsNotNull());
    }

    /// Conjoins this filter with `other` into a binary [RowFilter.And].
    ///
    /// @param other the filter to conjoin with this one
    /// @return an `And` over this filter and `other`
    default RowFilter and(RowFilter other) {
        return new And(List.of(this, other));
    }

    /// An n-ary conjunction of sub-filters: a row passes only when every sub-filter accepts it, and a
    /// chunk is pruned when any sub-filter prunes it.
    ///
    /// @param filters the conjoined sub-filters, must be non-null
    record And(List<RowFilter> filters) implements RowFilter {

        /// Validates the list.
        ///
        /// @param filters the conjoined sub-filters, must be non-null
        public And {
            Objects.requireNonNull(filters, "filters");
        }
    }

    /// A column bound to a value-test [Predicate]: the predicate is evaluated against `column`'s
    /// values (or, for pruning, against `column`'s zone-map statistics).
    ///
    /// @param column    the column name, must be non-null
    /// @param predicate the value-test to apply to the column, must be non-null
    record Column(ColumnName column, Predicate predicate) implements RowFilter {

        /// Validates both components.
        ///
        /// @param column    the column name, must be non-null
        /// @param predicate the value-test to apply to the column, must be non-null
        public Column {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(predicate, "predicate");
        }
    }
}
