package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.reader.RowFilter;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.interpreter.Bindables;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.NlsString;
import org.apache.calcite.util.Util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Rewrites a whole-table `MIN`/`MAX`/`COUNT`/`SUM` aggregate over a [VortexTable] into a
/// single-row [LogicalValues] computed from the footer zone-map statistics — answering the query
/// without decoding a single data segment (ADR 0013 §6, ADR 0018 Phase 2).
///
/// A `GROUP BY` on one dictionary-encoded string column is rewritten the same way, into one
/// [LogicalValues] row per group, folded per dictionary code instead of from statistics.
///
/// Fires only when it can answer *every* aggregate from statistics: no `GROUP BY`, and each call
/// is `COUNT(*)`, `COUNT(col)`, `MIN(col)`, `MAX(col)` (numeric or `VARCHAR`/`CHAR`), or `SUM(col)`
/// over a numeric column, with no `DISTINCT` and no `FILTER (WHERE ...)` clause. `SUM` folds the per-zone `SUM` rows via [VortexTable#zoneSum(ColumnName)]; it
/// emits the SQL `NULL` of an all-null (or empty) column, and abandons (falling back to the scan)
/// for a column whose zone-map table cannot answer it — no zone map, an overflowed zone, or a zero
/// fold whose null count is unknown (a genuine zero is indistinguishable from all-null). Anything
/// else (a grouped aggregate, `SUM` on a non-numeric column, `AVG` that was not reduced to
/// `SUM`/`COUNT`) leaves the plan untouched for the normal scan path.
// Calcite 1.40 removed RelRule.Config.EMPTY; the modern RelRule.Config path requires the
// Immutables annotation processor. The classic operand() constructor is deprecated but fully
// supported and far lighter for a single adapter rule — suppression is localized and justified.
@SuppressWarnings("deprecation")
public final class VortexAggregatePushDownRule extends RelOptRule {

    /// Matches `Aggregate(Project(TableScan))` — the shape Calcite produces when columns are
    /// selected before aggregation (e.g. `MIN(low)`).
    public static final VortexAggregatePushDownRule WITH_PROJECT = new VortexAggregatePushDownRule(
            operand(Aggregate.class, operand(Project.class, operand(TableScan.class, none()))),
            "VortexAggregatePushDownRule:project");

    /// Matches `Aggregate(TableScan)` — e.g. a bare `COUNT(*)` with no projected columns.
    public static final VortexAggregatePushDownRule NO_PROJECT = new VortexAggregatePushDownRule(
            operand(Aggregate.class, operand(TableScan.class, none())),
            "VortexAggregatePushDownRule:scan");

    /// Matches `Aggregate(Project(Filter(TableScan)))` — a `WHERE`-filtered aggregate with a column
    /// projection (e.g. `SUM(val) ... WHERE id < k`). At the logical level the predicate is a
    /// `Filter` node; it only folds into the scan in the physical plan, so the rule must match it
    /// here to answer the filtered aggregate from the kept zones' statistics.
    public static final VortexAggregatePushDownRule PROJECT_FILTER = new VortexAggregatePushDownRule(
            operand(Aggregate.class, operand(Project.class,
                    operand(Filter.class, operand(TableScan.class, none())))),
            "VortexAggregatePushDownRule:project-filter");

    /// Matches `Aggregate(Filter(TableScan))` — a `WHERE`-filtered aggregate with no projection
    /// (e.g. `COUNT(*) ... WHERE id < k`).
    public static final VortexAggregatePushDownRule FILTER_ONLY = new VortexAggregatePushDownRule(
            operand(Aggregate.class, operand(Filter.class, operand(TableScan.class, none()))),
            "VortexAggregatePushDownRule:filter");

    /// Every rule variant, for registering with a planner in one call.
    public static final java.util.List<RelOptRule> RULES =
            java.util.List.of(WITH_PROJECT, NO_PROJECT, PROJECT_FILTER, FILTER_ONLY);

    /// Plan-time folds per planning run. Calcite fires this rule several times per query (6 to 9
    /// measured), once per matching operand and subset, and each firing would re-read the columns.
    /// Keyed weakly by the planner, so a fold lives as long as its query's planning and the next
    /// query computes its own.
    private static final Map<RelOptPlanner, Map<List<Object>, Object>> FOLDS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private VortexAggregatePushDownRule(RelOptRuleOperand operand, String description) {
        super(operand, description);
    }

    /// Returns the fold cached under `key` for `planner`'s run, computing it on the first call.
    @SuppressWarnings("unchecked")
    static <T> T memo(RelOptPlanner planner, List<Object> key, java.util.function.Supplier<T> compute) {
        Map<List<Object>, Object> folds = FOLDS.computeIfAbsent(planner, p -> new java.util.concurrent.ConcurrentHashMap<>());
        return (T) folds.computeIfAbsent(key, k -> compute.get());
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        Aggregate aggregate = call.rel(0);
        if (aggregate.getGroupCount() > 1 || aggregate.getGroupType() != Aggregate.Group.SIMPLE) {
            return;
        }
        // Statistics describe every row of a zone, so they cannot answer an aggregate over a subset
        // of its rows: COUNT(DISTINCT g) is not COUNT(g), and a FILTER (WHERE ...) clause narrows
        // the rows one call sees. Leave either to the scan.
        for (AggregateCall aggCall : aggregate.getAggCallList()) {
            if (aggCall.isDistinct() || aggCall.hasFilter()) {
                return;
            }
        }
        // Explicit operands give concrete rels under both Hep and Volcano. Walk the optional
        // Project and Filter nodes between the Aggregate and the TableScan; each rule variant fixes
        // the shape, so the levels present here are exactly those its operand declared.
        Project project = null;
        Filter filter = null;
        RelNode current = call.rel(1);
        int index = 1;
        if (current instanceof Project p) {
            project = p;
            current = call.rel(++index);
        }
        if (current instanceof Filter f) {
            filter = f;
            current = call.rel(++index);
        }
        TableScan scan = (TableScan) current;
        VortexTable table = scan.getTable().unwrap(VortexTable.class);
        if (table == null) {
            return;
        }
        // A WHERE makes whole-table stats invalid. The predicate is the Filter node's condition at
        // the logical level (and BindableTableScan.filters once folded into a physical scan).
        // Translate it strictly; if every predicate is captured we may still answer from the zones
        // it selects (filteredFold), but only when it partitions them cleanly. An untranslatable
        // predicate abandons the rewrite so the scan computes the result over the filtered rows.
        List<RexNode> predicates = new ArrayList<>();
        if (filter != null) {
            predicates.add(filter.getCondition());
        }
        if (scan instanceof Bindables.BindableTableScan bindable) {
            predicates.addAll(bindable.filters);
        }
        RowFilter pushedFilter = null;
        if (!predicates.isEmpty()) {
            Optional<RowFilter> translated = table.translatePushedFilters(predicates);
            if (translated.isEmpty()) {
                return;
            }
            pushedFilter = translated.get();
        }
        RelDataType scanRowType = scan.getRowType();
        List<ColumnName> scanColumns = scanRowType.getFieldNames().stream().map(ColumnName::of).toList();

        RexBuilder rexBuilder = aggregate.getCluster().getRexBuilder();
        List<RelDataType> outTypes = aggregate.getRowType().getFieldList().stream()
                .map(f -> f.getType()).toList();

        if (aggregate.getGroupCount() == 1) {
            rewriteGrouped(call, aggregate, table, scanColumns, scanRowType, project, rexBuilder, outTypes,
                    pushedFilter);
            return;
        }

        List<RexLiteral> row = new ArrayList<>();
        List<AggregateCall> calls = aggregate.getAggCallList();
        for (int i = 0; i < calls.size(); i++) {
            RexLiteral literal = evaluate(calls.get(i), outTypes.get(i), table, scanColumns, scanRowType,
                    project, rexBuilder, pushedFilter, call.getPlanner());
            if (literal == null) {
                return; // an aggregate we can't answer from stats — abandon the rewrite
            }
            row.add(literal);
        }

        RelNode values = LogicalValues.create(
                aggregate.getCluster(), aggregate.getRowType(),
                ImmutableList.of(ImmutableList.copyOf(row)));
        call.transformTo(values);
    }

    /// Rewrites a `GROUP BY` on one string column into a [LogicalValues] with one row per group,
    /// folded per dictionary code by [VortexTable#groupedFold(ColumnName, RowFilter, java.util.Set)]
    /// instead of materializing every row for Calcite to hash. Leaves the plan alone when the group
    /// column is not a string, an aggregate is not `COUNT`/`SUM`/`MIN`/`MAX` over a plain column, or
    /// the fold declines (a chunk not dictionary-encoded, an aggregate column it cannot total, too
    /// many groups).
    private static void rewriteGrouped(RelOptRuleCall call, Aggregate aggregate, VortexTable table,
                                       List<ColumnName> scanColumns, RelDataType scanRowType, Project project,
                                       RexBuilder rexBuilder, List<RelDataType> outTypes, RowFilter filter) {
        ColumnName group = resolveColumn(aggregate.getGroupSet().nth(0), scanColumns, project);
        RelDataTypeField groupField = group == null ? null : scanRowType.getField(group.value(), true, false);
        if (groupField == null || !SqlTypeName.CHAR_TYPES.contains(groupField.getType().getSqlTypeName())) {
            return;
        }
        List<AggregateCall> calls = aggregate.getAggCallList();
        List<ColumnName> callColumns = new ArrayList<>();
        java.util.Set<ColumnName> aggColumns = new java.util.LinkedHashSet<>();
        for (AggregateCall aggCall : calls) {
            SqlKind kind = aggCall.getAggregation().getKind();
            if (kind == SqlKind.COUNT && aggCall.getArgList().isEmpty()) {
                callColumns.add(null);
                continue;
            }
            boolean supported = kind == SqlKind.COUNT || kind == SqlKind.SUM || kind == SqlKind.MIN
                    || kind == SqlKind.MAX;
            ColumnName column = aggCall.getArgList().size() == 1
                    ? resolveColumn(aggCall.getArgList().getFirst(), scanColumns, project) : null;
            if (!supported || column == null) {
                return;
            }
            callColumns.add(column);
            aggColumns.add(column);
        }
        // ConcurrentHashMap keys cannot be null, so a missing filter is keyed as an empty list
        List<Object> foldKey = List.of("grouped", table, group, filter == null ? List.of() : filter, aggColumns);
        Optional<Map<String, VortexTable.GroupFold>> folds =
                memo(call.getPlanner(), foldKey, () -> table.groupedFold(group, filter, aggColumns));
        if (folds.isEmpty()) {
            return;
        }
        RelDataType groupType = aggregate.getRowType().getFieldList().getFirst().getType();
        List<ImmutableList<RexLiteral>> tuples = new ArrayList<>();
        for (Map.Entry<String, VortexTable.GroupFold> entry : folds.get().entrySet()) {
            RexLiteral key = entry.getKey() == null ? rexBuilder.makeNullLiteral(groupType)
                    : minMaxLiteral(rexBuilder, entry.getKey(), groupType);
            if (key == null) {
                return; // a key the column's charset cannot hold as a literal
            }
            List<RexLiteral> row = new ArrayList<>();
            row.add(key);
            for (int i = 0; i < calls.size(); i++) {
                RexLiteral literal = groupedLiteral(calls.get(i), callColumns.get(i), entry.getValue(),
                        outTypes.get(i + 1), scanRowType, rexBuilder);
                if (literal == null) {
                    return;
                }
                row.add(literal);
            }
            tuples.add(ImmutableList.copyOf(row));
        }
        call.transformTo(LogicalValues.create(aggregate.getCluster(), aggregate.getRowType(),
                ImmutableList.copyOf(tuples)));
    }

    /// One aggregate call's literal for one group, or `null` when it cannot be built.
    private static RexLiteral groupedLiteral(AggregateCall aggCall, ColumnName column, VortexTable.GroupFold fold,
                                             RelDataType outType, RelDataType scanRowType, RexBuilder rexBuilder) {
        if (column == null) {
            return exact(rexBuilder, fold.rows(), outType);
        }
        VortexTable.FilteredFold columnFold = fold.columns().get(column);
        long nulls = columnFold.nullCount();
        return switch (aggCall.getAggregation().getKind()) {
            case COUNT -> exact(rexBuilder, columnFold.rows() - nulls, outType);
            case SUM -> sumLiteral(rexBuilder, outType, columnFold.sum(), nulls, columnFold.rows(),
                    isNullable(scanRowType, column.value()));
            case MIN, MAX -> {
                Object value = aggCall.getAggregation().getKind() == SqlKind.MIN ? columnFold.min() : columnFold.max();
                yield value == null ? rexBuilder.makeNullLiteral(outType) : minMaxLiteral(rexBuilder, value, outType);
            }
            default -> null;
        };
    }

    /// Evaluates one aggregate call from zone-map stats, returning a literal of `outType`, or
    /// `null` if it cannot be answered (so the caller abandons the rewrite). When `filter` is
    /// non-null the answer is folded from only the zones the filter fully selects, which exists
    /// only if the filter partitions the zones cleanly — otherwise the fold is empty and the call
    /// abandons.
    private static RexLiteral evaluate(AggregateCall agg, RelDataType outType, VortexTable table,
                                       List<ColumnName> scanColumns, RelDataType scanRowType,
                                       Project project, RexBuilder rexBuilder, RowFilter filter,
                                       RelOptPlanner planner) {
        return switch (agg.getAggregation().getKind()) {
            case COUNT -> {
                if (agg.getArgList().isEmpty()) {                  // COUNT(*)
                    if (filter == null) {
                        yield exact(rexBuilder, table.totalRows(), outType);
                    }
                    Optional<VortexTable.FilteredFold> fold = filteredFold(planner, table, filter, null);
                    yield fold.map(f -> exact(rexBuilder, f.rows(), outType)).orElse(null);
                }
                ColumnName col = resolveColumn(agg.getArgList().getFirst(), scanColumns, project);
                if (col == null) {
                    yield null;
                }
                ColumnFold fold = columnFold(table, col, filter, planner);
                if (fold == null) {
                    yield null;
                }
                // COUNT(col) = rows − nulls. Without a NULL_COUNT stat we cannot assume zero nulls
                // for a nullable column (we would overcount), so abandon; a non-nullable column has
                // no nulls and is safe.
                if (fold.nullCount() == null && isNullable(scanRowType, col.value())) {
                    yield null;
                }
                long nulls = fold.nullCount() == null ? 0L : fold.nullCount();
                yield exact(rexBuilder, fold.rows() - nulls, outType);
            }
            case MIN, MAX -> {
                if (agg.getArgList().size() != 1) {
                    yield null;
                }
                ColumnName col = resolveColumn(agg.getArgList().getFirst(), scanColumns, project);
                if (col == null) {
                    yield null;
                }
                ColumnFold fold = columnFold(table, col, filter, planner);
                if (fold == null) {
                    yield null;
                }
                Object value = agg.getAggregation().getKind() == SqlKind.MIN ? fold.min() : fold.max();
                if (value == null) {
                    // No MIN/MAX stat. A genuine SQL NULL is only correct when the (selected) rows
                    // provably have no non-null value — empty set, or every row null; otherwise the
                    // stat is merely absent and we must abandon so the scan computes the real value.
                    boolean provablyNoValues = fold.rows() == 0
                            || (fold.nullCount() != null && fold.nullCount() == fold.rows());
                    yield provablyNoValues ? rexBuilder.makeNullLiteral(outType) : null;
                }
                yield minMaxLiteral(rexBuilder, value, outType);
            }
            case SUM -> {
                if (agg.getArgList().size() != 1) {
                    yield null;
                }
                ColumnName col = resolveColumn(agg.getArgList().getFirst(), scanColumns, project);
                if (col == null) {
                    yield null;
                }
                ColumnFold fold = columnFold(table, col, filter, planner);
                if (fold == null) {
                    yield null;
                }
                yield sumLiteral(rexBuilder, outType, fold.sum(), fold.nullCount(), fold.rows(),
                        isNullable(scanRowType, col.value()));
            }
            default -> null;
        };
    }

    /// The sum, min, max, null count and row count of a column over either the whole table
    /// (`filter == null`) or the rows a clean-partitioning `filter` selects — the common shape the
    /// `SUM`/`MIN`/`MAX`/`COUNT(col)` branches read. `null` for a filtered call whose zones the
    /// filter does not partition cleanly (so the caller abandons to the scan).
    private record ColumnFold(Number sum, Object min, Object max, Long nullCount, long rows) {
    }

    private static ColumnFold columnFold(VortexTable table, ColumnName column, RowFilter filter,
                                         RelOptPlanner planner) {
        if (filter == null) {
            VortexTable.ColumnStats stats = table.statsAndRows(column);
            VortexTable.ZoneSum zoneSum = table.zoneSum(column);
            return new ColumnFold(zoneSum.sum(), stats.stats().min(), stats.stats().max(),
                    stats.stats().nullCount(), stats.totalRows());
        }
        return filteredFold(planner, table, filter, column)
                .map(f -> new ColumnFold(f.sum(), f.min(), f.max(), f.nullCount(), f.rows()))
                .orElse(null);
    }

    /// [VortexTable#filteredFold(RowFilter, ColumnName)], computed once per planner run.
    private static Optional<VortexTable.FilteredFold> filteredFold(RelOptPlanner planner, VortexTable table,
                                                                    RowFilter filter, ColumnName column) {
        List<Object> foldKey = List.of("filtered", table, filter, column == null ? List.of() : column);
        return memo(planner, foldKey, () -> table.filteredFold(filter, column));
    }

    /// Builds the literal for a `SUM`, resolving the all-null-vs-genuine-zero ambiguity from the
    /// null count. SQL `SUM` over zero non-null rows is `NULL`, but the writer records an all-null
    /// zone's sum as a sum-neutral `0` (validity placeholders are zero), so a `0` fold alone cannot
    /// tell an all-null column from a genuine zero:
    ///   - `sum == null` — a folded zone carried no usable sum → abandon to the scan;
    ///   - provably no non-null rows (empty, or `nullCount == rows`) → the SQL `NULL` literal;
    ///   - a non-zero fold → at least one non-null row, the number is correct;
    ///   - a `0` fold with an unknown null count over a nullable column → ambiguous, so abandon.
    /// A non-nullable column has no nulls, so any fold is the true sum.
    private static RexLiteral sumLiteral(RexBuilder rexBuilder, RelDataType outType, Number sum,
                                         Long nullCount, long rows, boolean nullable) {
        if (sum == null) {
            return null;
        }
        if (rows == 0 || (nullCount != null && nullCount == rows)) {
            return rexBuilder.makeNullLiteral(outType);
        }
        if (isZero(sum) && nullCount == null && nullable) {
            return null;
        }
        return numericLiteral(rexBuilder, sum, outType);
    }

    /// Returns whether column `col` is nullable per the scan's row type.
    private static boolean isNullable(RelDataType scanRowType, String col) {
        RelDataTypeField field = scanRowType.getField(col, true, false);
        return field == null || field.getType().isNullable();
    }

    /// Maps an aggregate input ordinal to a scan column name, looking through a `Project` of input
    /// refs when present. Returns `null` if the ordinal is a computed expression, not a column.
    private static ColumnName resolveColumn(int aggInput, List<ColumnName> scanColumns, Project project) {
        if (project == null) {
            return aggInput < scanColumns.size() ? scanColumns.get(aggInput) : null;
        }
        RexNode expr = project.getProjects().get(aggInput);
        if (expr instanceof RexInputRef ref && ref.getIndex() < scanColumns.size()) {
            return scanColumns.get(ref.getIndex());
        }
        return null;
    }

    /// Returns whether a folded sum is numerically zero (`0L` or `0.0`) — the value an all-null zone
    /// records, indistinguishable from a genuine zero sum without a null count.
    private static boolean isZero(Number sum) {
        return switch (sum) {
            case Long l -> l == 0L;
            case Double d -> d == 0.0;
            default -> sum.doubleValue() == 0.0;
        };
    }

    private static RexLiteral exact(RexBuilder rexBuilder, long value, RelDataType type) {
        return rexBuilder.makeExactLiteral(BigDecimal.valueOf(value), type);
    }

    /// Builds a literal for a non-null `MIN`/`MAX` value, or `null` (the rule abandons) for a string
    /// the type's charset cannot encode: a [String] zone-map stat (a `Utf8` column,
    /// whose min/max the writer records as the full string, not a numeric summary) against a
    /// `CHAR`/`VARCHAR` output type, wrapped as an [NlsString] — [RexBuilder#makeLiteral(Object,
    /// RelDataType)] asserts a `CHAR`-family value already comes in that form, populating charset
    /// and collation from `type` when the wrapper doesn't carry them. Anything else falls to
    /// [#numericLiteral(RexBuilder, Object, RelDataType)].
    private static RexLiteral minMaxLiteral(RexBuilder rexBuilder, Object value, RelDataType type) {
        if (value instanceof String s
                && (type.getSqlTypeName() == SqlTypeName.VARCHAR || type.getSqlTypeName() == SqlTypeName.CHAR)) {
            // A literal must fit the type's charset, ISO-8859-1 unless declared otherwise; one that
            // does not fails the whole query, so leave it to the scan, which builds no literal.
            java.nio.charset.Charset charset = type.getCharset() != null ? type.getCharset() : Util.getDefaultCharset();
            if (!charset.newEncoder().canEncode(s)) {
                return null;
            }
            return rexBuilder.makeLiteral(new NlsString(s, null, null), type);
        }
        return numericLiteral(rexBuilder, value, type);
    }

    /// Builds a literal for a non-null numeric value, supporting only numeric output types (exact
    /// and approximate). A non-numeric value yields `null` so the rule abandons the rewrite — the
    /// only caller for a non-numeric value is [#minMaxLiteral(RexBuilder, Object, RelDataType)],
    /// which has already handled the `String`/`CHAR`-family case before falling here.
    private static RexLiteral numericLiteral(RexBuilder rexBuilder, Object value, RelDataType type) {
        if (!(value instanceof Number number)) {
            return null;
        }
        return switch (type.getSqlTypeName()) {
            case TINYINT, SMALLINT, INTEGER, BIGINT ->
                    rexBuilder.makeExactLiteral(BigDecimal.valueOf(number.longValue()), type);
            case FLOAT, REAL, DOUBLE, DECIMAL ->
                    rexBuilder.makeApproxLiteral(BigDecimal.valueOf(number.doubleValue()), type);
            default -> null;
        };
    }
}
