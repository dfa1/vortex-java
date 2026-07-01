package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.array.Array;

import java.util.Objects;

/// The minimal, low-level entry point to the compute primitives of ADR 0013 — the fused, single-pass
/// filter-and-aggregate kernels.
///
/// This is deliberately the *only* public surface: the kernels behind it stay package-private per
/// the ADR risk note, so early callers do not couple to a kernel shape that a future façade ADR
/// (transducer / Stream / fluent builder) will replace. There is no builder, no pipeline, no rich
/// API here — just the primitives.
///
/// Both kernels fold the filter and the reduce in a single pass with no intermediate selection bitmap:
/// [#filteredSum(Array, Predicate, Array)] filters one column and totals a second; the multi-column
/// [#filteredAggregate(Chunk, RowFilter, String)] evaluates a whole [RowFilter] and folds an
/// aggregate column's `SUM` / `MIN` / `MAX` / non-null count over the rows it selects.
///
/// Null handling follows the Rust reference: a filter excludes null positions (a null value makes a
/// value predicate false), and the reduce skips them — `SUM` and `COUNT` over zero selected non-null
/// rows return the identity (`0`), while `MIN` and `MAX` return `null`.
public final class Compute {

    private Compute() {
    }

    /// Filters `filterColumn` by `predicate` and sums `aggColumn` over the selected rows in a single
    /// fused pass.
    ///
    /// For each row it evaluates the predicate and folds the aggregate value in one loop over the rows
    /// — no intermediate bitmap and no second pass. A null filter row is never selected (three-valued
    /// logic) and a null aggregate row is skipped.
    ///
    /// @param filterColumn the column `predicate` tests
    /// @param predicate    the predicate that selects rows
    /// @param aggColumn    the numeric column to total over the selected rows, of the same length as
    ///                     `filterColumn`
    /// @return the sum as a [Long] for an integer aggregate column or a [Double] for a floating one;
    ///         the additive identity (`0`) when no row is both selected and non-null
    public static Number filteredSum(Array filterColumn, Predicate predicate, Array aggColumn) {
        Objects.requireNonNull(filterColumn, "filterColumn");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(aggColumn, "aggColumn");
        return FusedFilterSum.filteredSum(filterColumn, predicate, aggColumn);
    }

    /// Evaluates `filter` over `chunk` and folds `aggColumn` over the rows it selects, in one fused
    /// pass — the multi-column counterpart of [#filteredSum(Array, Predicate, Array)].
    ///
    /// The `filter` is the whole chunk predicate: an n-ary `AND` of column-bound [Predicate] leaves
    /// (or a single leaf). A row is selected only when every leaf accepts it under SQL three-valued
    /// logic — a null in any filter column rejects that leaf. Over the selected rows whose aggregate
    /// value is non-null, the kernel folds the aggregate column's `SUM`, `MIN`, `MAX` and non-null
    /// count in the same scan, with no intermediate bitmap.
    ///
    /// `aggColumn` may be `null` for a `COUNT(*)`-style fold that only counts selected rows; the
    /// returned [FilteredAggregate]'s aggregate fields are then empty. `SUM` is `null` for a
    /// non-numeric aggregate column (an unanswerable sum); `MIN` / `MAX` are `null` when no selected
    /// row is non-null. The aggregate's null count among the selected rows is
    /// `selectedRows − aggNonNullCount`.
    ///
    /// @param chunk     the decoded chunk holding the filter and aggregate columns
    /// @param filter    the whole chunk predicate to evaluate
    /// @param aggColumn the column to reduce over the selected rows, or `null` to count rows only
    /// @return the fold over the rows `filter` selects
    public static FilteredAggregate filteredAggregate(Chunk chunk, RowFilter filter, String aggColumn) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(filter, "filter");
        return FusedFilterAggregate.aggregate(chunk, filter, aggColumn);
    }
}
