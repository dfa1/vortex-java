package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanFilter;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/// The minimal, low-level entry point to the compute primitives of ADR 0013 — the fused, single-pass
/// filter-and-aggregate kernels.
///
/// This is deliberately the *only* public surface: the kernels behind it stay package-private per
/// the ADR risk note, so early callers do not couple to a kernel shape that a future façade ADR
/// (transducer / Stream / fluent builder) will replace. There is no builder, no pipeline, no rich
/// API here — just the primitives.
///
/// [#filteredAggregate(Chunk, ScanFilter, ColumnName)] folds the filter and the reduce in a single
/// pass with no intermediate selection bitmap: it evaluates a whole [ScanFilter] and folds an
/// aggregate column's `SUM` / `MIN` / `MAX` / non-null count over the rows it selects.
/// [#matches(Chunk, ScanFilter, long)] is its un-fused, per-row counterpart: a plain boolean test
/// with no aggregate at all, for a caller that needs to select rows rather than reduce them;
/// [#matcher(Chunk, ScanFilter)] lowers that test once per chunk for a caller testing many rows.
///
/// Null handling follows the Rust reference: a filter excludes null positions (a null value makes a
/// value predicate false), and the reduce skips them — `SUM` and `COUNT` over zero selected non-null
/// rows return the identity (`0`), while `MIN` and `MAX` return `null`.
public final class Compute {

    private Compute() {
    }

    /// Evaluates `filter` over `chunk` and folds `aggColumn` over the rows it selects, in one fused
    /// pass.
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
    public static FilteredAggregate filteredAggregate(Chunk chunk, ScanFilter filter, ColumnName aggColumn) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(filter, "filter");
        return FusedFilterAggregate.aggregate(chunk, filter, aggColumn);
    }

    /// Evaluates `filter` against a single row of `chunk`'s already-decoded columns, the un-fused
    /// counterpart of [#filteredAggregate(Chunk, ScanFilter, ColumnName)] for a caller that needs one
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
    public static boolean matches(Chunk chunk, ScanFilter filter, long row) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(filter, "filter");
        return evaluate(chunk, filter, row);
    }

    /// Lowers `filter` once for `chunk` and returns the row test, the form of
    /// [#matches(Chunk, ScanFilter, long)] for a caller that tests many rows of one chunk.
    ///
    /// The per-leaf work that does not depend on the row is done here rather than per row: a
    /// numeric comparison resolves its bounds once, and a comparison over a dictionary-encoded
    /// string column is evaluated once per dictionary entry and its codes are mapped to a bitmap of
    /// the selected rows, which [RowSelection#nextSelected(long)] walks 64 rows at a time. The
    /// selected rows are the same as [#matches(Chunk, ScanFilter, long)].
    ///
    /// @param chunk  the decoded chunk holding every column `filter` references
    /// @param filter the whole predicate: an n-ary `AND` of column-bound leaves, or a single leaf
    /// @return the rows of `chunk` that `filter` selects, valid while `chunk` is open
    public static RowSelection matcher(Chunk chunk, ScanFilter filter) {
        Objects.requireNonNull(chunk, "chunk");
        return matcher(filter).apply(chunk);
    }

    /// [#matcher(Chunk, ScanFilter)] for the chunks of one scan, in order: work that depends only on a
    /// dictionary the chunks share, as the chunks of Rust's dict layout do, is done once for the scan
    /// instead of once per chunk. Not for concurrent use.
    ///
    /// @param filter the whole predicate: an n-ary `AND` of column-bound leaves, or a single leaf
    /// @return a function from a chunk to the rows it selects, each valid while its chunk is open
    public static Function<Chunk, RowSelection> matcher(ScanFilter filter) {
        Objects.requireNonNull(filter, "filter");
        FusedFilterAggregate.DictMemo memo = new FusedFilterAggregate.DictMemo();
        return chunk -> FusedFilterAggregate.rowTest(Objects.requireNonNull(chunk, "chunk"), filter, memo);
    }

    /// Folds `aggColumn` per value of `groupColumn` over the rows `filter` selects: the per-group
    /// counterpart of [#filteredAggregate(Chunk, ScanFilter, ColumnName)].
    ///
    /// Rows accumulate per group id in primitive arrays, so a row costs a key read and a few array
    /// updates, and no key is boxed per row. The group column may be a dictionary-encoded string
    /// column (keys are `String`s, each code turned into its string once per chunk), a boolean column
    /// (`Boolean` keys) or an integer column other than `u64` (`Long` keys, unsigned values
    /// zero-extended). A null group value is its own group, under the `null` key. Each group's
    /// [FilteredAggregate] follows the same null semantics as the ungrouped fold.
    ///
    /// The result is empty when the shapes do not qualify: another group column type, a string
    /// column not dictionary-encoded in this chunk, more than 65,536 groups in the chunk, or an
    /// `aggColumn` that is not an integer, floating or decimal column (`f16`, `u64` and decimals past
    /// precision 18 included, which this fold does not total; a decimal folds exactly, as unscaled
    /// longs, and a decimal sum past a long declines too). The caller then groups the rows itself.
    ///
    /// @param chunk       the decoded chunk holding every referenced column
    /// @param groupColumn the column to group by
    /// @param filter      the rows to fold, or `null` for every row
    /// @param aggColumn   the numeric column to reduce, or `null` to count rows only
    /// @return the fold per group value, or empty when the columns do not qualify
    public static Optional<Map<Object, FilteredAggregate>> groupedAggregate(Chunk chunk, ColumnName groupColumn,
                                                                            ScanFilter filter, ColumnName aggColumn) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(groupColumn, "groupColumn");
        return GroupedAggregate.aggregate(chunk, groupColumn, filter, aggColumn);
    }

    private static boolean evaluate(Chunk chunk, ScanFilter filter, long row) {
        return switch (filter) {
            case ScanFilter.And(var parts) -> {
                for (ScanFilter part : parts) {
                    if (!evaluate(chunk, part, row)) {
                        yield false;
                    }
                }
                yield true;
            }
            case ScanFilter.Column(var col, var predicate) ->
                    PredicateEvaluator.evaluate(chunk.column(col), row, predicate);
        };
    }
}
