package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.array.Array;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongPredicate;

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
/// [#filteredAggregate(Chunk, RowFilter, ColumnName)] evaluates a whole [RowFilter] and folds an
/// aggregate column's `SUM` / `MIN` / `MAX` / non-null count over the rows it selects.
/// [#matches(Chunk, RowFilter, long)] is the un-fused, per-row counterpart of the two: a plain
/// boolean test with no aggregate at all, for a caller that needs to select rows rather than reduce
/// them; [#matcher(Chunk, RowFilter)] lowers that test once per chunk for a caller testing many rows.
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
    public static FilteredAggregate filteredAggregate(Chunk chunk, RowFilter filter, ColumnName aggColumn) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(filter, "filter");
        return FusedFilterAggregate.aggregate(chunk, filter, aggColumn);
    }

    /// Evaluates `filter` against a single row of `chunk`'s already-decoded columns, the un-fused
    /// counterpart of [#filteredAggregate(Chunk, RowFilter, ColumnName)] for a caller that needs one
    /// row's yes/no answer rather than a folded aggregate — for example, a downstream adapter that
    /// decodes a chunk once and streams only the rows a pushed filter selects, instead of returning
    /// every row for its caller to re-check row-by-row.
    ///
    /// Each column-bound leaf tests through the per-element [PredicateEvaluator], so a row is
    /// selected only when every leaf accepts it under the same three-valued logic (a null value
    /// never satisfies a value predicate) as the fused kernels.
    ///
    /// @param chunk  the decoded chunk holding every column `filter` references
    /// @param filter the whole predicate: an n-ary `AND` of column-bound leaves, or a single leaf
    /// @param row    the zero-based row to test
    /// @return `true` if `row` satisfies `filter`
    public static boolean matches(Chunk chunk, RowFilter filter, long row) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(filter, "filter");
        return evaluate(chunk, filter, row);
    }

    /// Lowers `filter` once for `chunk` and returns the row test, the form of
    /// [#matches(Chunk, RowFilter, long)] for a caller that tests many rows of one chunk.
    ///
    /// The per-leaf work that does not depend on the row is done here rather than per row: a
    /// numeric comparison resolves its bounds once, and a comparison over a dictionary-encoded
    /// string column is evaluated once per dictionary entry, so each row costs a code lookup instead
    /// of a string comparison. The selected rows are the same as [#matches(Chunk, RowFilter, long)].
    ///
    /// @param chunk  the decoded chunk holding every column `filter` references
    /// @param filter the whole predicate: an n-ary `AND` of column-bound leaves, or a single leaf
    /// @return a test of a zero-based row of `chunk`, valid while `chunk` is open
    public static LongPredicate matcher(Chunk chunk, RowFilter filter) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(filter, "filter");
        return FusedFilterAggregate.rowTest(chunk, filter);
    }

    /// Folds `aggColumn` per value of a dictionary-encoded string `groupColumn` over the rows `filter`
    /// selects: the per-group counterpart of [#filteredAggregate(Chunk, RowFilter, ColumnName)].
    ///
    /// Rows accumulate per dictionary code, so a row costs a code read and a few array updates, and
    /// each code becomes its string once per chunk. A null group value is its own group, under the
    /// `null` key. Each group's [FilteredAggregate] follows the same null semantics as the ungrouped
    /// fold.
    ///
    /// The result is empty when the shapes do not qualify: `groupColumn` is not dictionary-encoded
    /// in this chunk, or `aggColumn` is not an integer or floating column (`f16` and `u64` included,
    /// which this fold does not total). The caller then groups the rows itself.
    ///
    /// @param chunk       the decoded chunk holding every referenced column
    /// @param groupColumn the dictionary-encoded string column to group by
    /// @param filter      the rows to fold, or `null` for every row
    /// @param aggColumn   the numeric column to reduce, or `null` to count rows only
    /// @return the fold per group value, or empty when the columns do not qualify
    public static Optional<Map<String, FilteredAggregate>> groupedAggregate(Chunk chunk, ColumnName groupColumn,
                                                                            RowFilter filter, ColumnName aggColumn) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(groupColumn, "groupColumn");
        return GroupedAggregate.aggregate(chunk, groupColumn, filter, aggColumn);
    }

    private static boolean evaluate(Chunk chunk, RowFilter filter, long row) {
        return switch (filter) {
            case RowFilter.And(var parts) -> {
                for (RowFilter part : parts) {
                    if (!evaluate(chunk, part, row)) {
                        yield false;
                    }
                }
                yield true;
            }
            case RowFilter.Column(var col, var predicate) ->
                    PredicateEvaluator.evaluate(chunk.column(col), row, predicate);
        };
    }
}
