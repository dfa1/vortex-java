package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.array.Array;

import java.lang.foreign.Arena;
import java.util.Objects;

/// The minimal, low-level entry point to the compute primitives of ADR 0013 — filter an [Array] into
/// a selection [Mask] and reduce a masked [Array] to a scalar.
///
/// This is deliberately the *only* public surface: the kernels behind it stay package-private per
/// the ADR risk note, so early callers do not couple to a kernel shape that a future façade ADR
/// (transducer / Stream / fluent builder) will replace. There is no builder, no pipeline, no rich
/// API here — just the primitives.
///
/// Null handling follows the Rust reference: a filter excludes null positions (a null value makes a
/// value predicate false), and a reduce skips them — `SUM` and `COUNT` over zero selected non-null
/// rows return the identity (`0`), while `MIN` and `MAX` return `null`.
public final class Compute {

    private static final FilterKernel FILTER = new StreamingFilterKernel();

    private Compute() {
    }

    /// Filters `array` by `predicate`, returning the positions it accepts as a [Mask].
    ///
    /// Every position starts selected; the returned mask has the same length as `array` and selects
    /// exactly the positions whose value satisfies `predicate`. Null positions are excluded from a
    /// value predicate. The output bitmap is allocated off-heap from `arena`.
    ///
    /// @param array     the array to filter
    /// @param predicate the predicate to evaluate per position
    /// @param arena     the arena for the output bitmap; its [Arena#allocate(long)] zero-fills, which
    ///                  seeds the unselected bits to 0
    /// @return a mask of `array.length()` positions selected where `predicate` holds
    /// @deprecated Materializing a selection [Mask] is a slower primitive than a fused single-pass
    ///             scan: it builds a positional bitmap over the whole filter column, which a later
    ///             reduce must re-scan. Prefer the fused [#filteredSum(Array, Predicate, Array)] (and
    ///             the forthcoming fused multi-column `filteredReduce`), which fold filter and
    ///             aggregate in one pass with no intermediate bitmap. Scheduled for removal once the
    ///             Calcite boundary fold migrates off it.
    @Deprecated(since = "0.12.0", forRemoval = true)
    @SuppressWarnings("removal") // suppresses only this method's own use of the to-be-removed Mask return type — callers still see filter() as deprecated
    public static Mask filter(Array array, Predicate predicate, Arena arena) {
        Objects.requireNonNull(array, "array");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(arena, "arena");
        return FILTER.apply(array, Mask.allTrue(array.length()), predicate, arena);
    }

    /// Sums the selected non-null values of `array`.
    ///
    /// Pairs with the (deprecated) [Mask]; transitional pending the fused reductions such as
    /// [#filteredSum(Array, Predicate, Array)].
    ///
    /// @param array the array to reduce
    /// @param mask  the selection mask, must have the same length as `array`
    /// @return the sum as a [Long] for integer columns or a [Double] for floating columns; the
    ///         additive identity (`0`) when no selected position is non-null
    @SuppressWarnings("removal") // transitional — consumes the deprecated Mask until the fused multi-column filteredReduce lands
    public static Number sum(Array array, Mask mask) {
        return Reductions.SUM.apply(array, requireMask(array, mask));
    }

    /// Filters `filterColumn` by `predicate` and sums `aggColumn` over the selected rows in a single
    /// fused pass, the one-pass counterpart to a [#filter(Array, Predicate, Arena)] followed by a
    /// [#sum(Array, Mask)].
    ///
    /// Where the two-pass path materializes a positional [Mask] over the whole filter column and then
    /// re-scans the aggregate column under it, this kernel evaluates the predicate and folds the
    /// aggregate value in one loop over the rows — no intermediate mask, no bitmap, no second pass.
    /// The result is identical to the two-pass path: a null filter row is never selected (three-valued
    /// logic, like [#filter(Array, Predicate, Arena)]) and a null aggregate row is skipped (like
    /// [#sum(Array, Mask)]).
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
    /// pass — the multi-column counterpart of [#filteredSum(Array, Predicate, Array)] and the
    /// replacement for building per-leaf [Mask]s, intersecting them, and reducing under the result.
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

    /// Counts the selected non-null values of `array`.
    ///
    /// Pairs with the (deprecated) [Mask]; transitional pending the fused reductions such as
    /// [#filteredSum(Array, Predicate, Array)].
    ///
    /// @param array the array to reduce
    /// @param mask  the selection mask, must have the same length as `array`
    /// @return the count of selected non-null values, `0` when none
    @SuppressWarnings("removal") // transitional — consumes the deprecated Mask until the fused multi-column filteredReduce lands
    public static long count(Array array, Mask mask) {
        return Reductions.COUNT.apply(array, requireMask(array, mask));
    }

    /// Finds the smallest selected non-null value of `array` under its dtype-aware order.
    ///
    /// Pairs with the (deprecated) [Mask]; transitional pending the fused reductions such as
    /// [#filteredSum(Array, Predicate, Array)].
    ///
    /// @param array the array to reduce
    /// @param mask  the selection mask, must have the same length as `array`
    /// @return the minimum value, or `null` when no selected position is non-null
    @SuppressWarnings("removal") // transitional — consumes the deprecated Mask until the fused multi-column filteredReduce lands
    public static Object min(Array array, Mask mask) {
        return Reductions.MIN.apply(array, requireMask(array, mask));
    }

    /// Finds the largest selected non-null value of `array` under its dtype-aware order.
    ///
    /// Pairs with the (deprecated) [Mask]; transitional pending the fused reductions such as
    /// [#filteredSum(Array, Predicate, Array)].
    ///
    /// @param array the array to reduce
    /// @param mask  the selection mask, must have the same length as `array`
    /// @return the maximum value, or `null` when no selected position is non-null
    @SuppressWarnings("removal") // transitional — consumes the deprecated Mask until the fused multi-column filteredReduce lands
    public static Object max(Array array, Mask mask) {
        return Reductions.MAX.apply(array, requireMask(array, mask));
    }

    /// Validates the array and mask arguments shared by the reductions.
    ///
    /// @param array the array to reduce
    /// @param mask  the selection mask
    /// @return the validated mask
    @SuppressWarnings("removal") // transitional — consumes the deprecated Mask until the fused multi-column filteredReduce lands
    private static Mask requireMask(Array array, Mask mask) {
        Objects.requireNonNull(array, "array");
        Objects.requireNonNull(mask, "mask");
        return mask;
    }
}
