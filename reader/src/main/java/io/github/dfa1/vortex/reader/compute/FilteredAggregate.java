package io.github.dfa1.vortex.reader.compute;

/// The result of a fused multi-column filtered aggregation over one chunk, returned by
/// [Compute#filteredAggregate(io.github.dfa1.vortex.reader.Chunk, io.github.dfa1.vortex.reader.RowFilter, String)].
///
/// The fold covers exactly the rows the whole [io.github.dfa1.vortex.reader.RowFilter] selects. The
/// aggregate fields are populated only when an aggregate column was given; with no aggregate column
/// (a `COUNT(*)`) only [#selectedRows()] is meaningful and the rest are zero / `null`.
///
/// Null semantics mirror the reductions: `SUM` over zero selected non-null rows is the additive
/// identity (`0`), while `sum` is `null` only when the aggregate column is non-numeric (an
/// unanswerable sum); `MIN` / `MAX` are `null` when no selected row is non-null. The aggregate
/// column's null count among the selected rows is `selectedRows − aggNonNullCount`.
///
/// @param selectedRows    the number of rows the whole filter selected
/// @param aggNonNullCount the number of selected rows whose aggregate value is non-null (`0` when
///                        there is no aggregate column)
/// @param sum             the `SUM` over the selected non-null rows ([Long] for an integer aggregate,
///                        [Double] for a floating one), `null` when there is no aggregate column or it
///                        is non-numeric
/// @param min             the `MIN` over the selected non-null rows under the column's dtype-aware
///                        order, or `null` when none contributed
/// @param max             the `MAX` over the selected non-null rows, or `null` when none contributed
public record FilteredAggregate(long selectedRows, long aggNonNullCount, Number sum, Object min, Object max) {
}
