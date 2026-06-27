package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.reader.RowFilter;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.interpreter.Bindables;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Rewrites a whole-table `MIN`/`MAX`/`COUNT`/`SUM` aggregate over a [VortexTable] into a
/// single-row [LogicalValues] computed from the footer zone-map statistics — answering the query
/// without decoding a single data segment (ADR 0013 §6, ADR 0018 Phase 2).
///
/// Fires only when it can answer *every* aggregate from statistics: no `GROUP BY`, and each call
/// is `COUNT(*)`, `COUNT(col)`, `MIN(col)`, `MAX(col)`, or `SUM(col)` over a numeric column. `SUM`
/// folds the per-zone `SUM` rows via [VortexTable#zoneSum(String)]; it emits the SQL `NULL` of an
/// all-null (or empty) column, and abandons (falling back to the scan) for a column whose zone-map
/// table cannot answer it — no zone map, an overflowed zone, or a zero fold whose null count is
/// unknown (a genuine zero is indistinguishable from all-null). Anything else (a grouped aggregate,
/// `MIN` on a non-numeric column, `AVG` that was not reduced to `SUM`/`COUNT`) leaves the plan
/// untouched for the normal scan path.
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

    private VortexAggregatePushDownRule(RelOptRuleOperand operand, String description) {
        super(operand, description);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        Aggregate aggregate = call.rel(0);
        if (aggregate.getGroupCount() != 0) {
            return;
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
        List<String> scanColumns = scanRowType.getFieldNames();

        RexBuilder rexBuilder = aggregate.getCluster().getRexBuilder();
        List<RelDataType> outTypes = aggregate.getRowType().getFieldList().stream()
                .map(f -> f.getType()).toList();

        List<RexLiteral> row = new ArrayList<>();
        List<AggregateCall> calls = aggregate.getAggCallList();
        for (int i = 0; i < calls.size(); i++) {
            RexLiteral literal = evaluate(calls.get(i), outTypes.get(i), table, scanColumns, scanRowType,
                    project, rexBuilder, pushedFilter);
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

    /// Evaluates one aggregate call from zone-map stats, returning a literal of `outType`, or
    /// `null` if it cannot be answered (so the caller abandons the rewrite). When `filter` is
    /// non-null the answer is folded from only the zones the filter fully selects, which exists
    /// only if the filter partitions the zones cleanly — otherwise the fold is empty and the call
    /// abandons.
    private static RexLiteral evaluate(AggregateCall agg, RelDataType outType, VortexTable table,
                                       List<String> scanColumns, RelDataType scanRowType,
                                       Project project, RexBuilder rexBuilder, RowFilter filter) {
        return switch (agg.getAggregation().getKind()) {
            case COUNT -> {
                if (agg.getArgList().isEmpty()) {                  // COUNT(*)
                    if (filter == null) {
                        yield exact(rexBuilder, table.totalRows(), outType);
                    }
                    Optional<VortexTable.FilteredFold> fold = table.filteredFold(filter, null);
                    yield fold.map(f -> exact(rexBuilder, f.rows(), outType)).orElse(null);
                }
                String col = resolveColumn(agg.getArgList().getFirst(), scanColumns, project);
                if (col == null) {
                    yield null;
                }
                ColumnFold fold = columnFold(table, col, filter);
                if (fold == null) {
                    yield null;
                }
                // COUNT(col) = rows − nulls. Without a NULL_COUNT stat we cannot assume zero nulls
                // for a nullable column (we would overcount), so abandon; a non-nullable column has
                // no nulls and is safe.
                if (fold.nullCount() == null && isNullable(scanRowType, col)) {
                    yield null;
                }
                long nulls = fold.nullCount() == null ? 0L : fold.nullCount();
                yield exact(rexBuilder, fold.rows() - nulls, outType);
            }
            case MIN, MAX -> {
                if (agg.getArgList().size() != 1) {
                    yield null;
                }
                String col = resolveColumn(agg.getArgList().getFirst(), scanColumns, project);
                if (col == null) {
                    yield null;
                }
                ColumnFold fold = columnFold(table, col, filter);
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
                yield numericLiteral(rexBuilder, value, outType);
            }
            case SUM -> {
                if (agg.getArgList().size() != 1) {
                    yield null;
                }
                String col = resolveColumn(agg.getArgList().getFirst(), scanColumns, project);
                if (col == null) {
                    yield null;
                }
                ColumnFold fold = columnFold(table, col, filter);
                if (fold == null) {
                    yield null;
                }
                yield sumLiteral(rexBuilder, outType, fold.sum(), fold.nullCount(), fold.rows(),
                        isNullable(scanRowType, col));
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

    private static ColumnFold columnFold(VortexTable table, String column, RowFilter filter) {
        if (filter == null) {
            VortexTable.ColumnStats stats = table.statsAndRows(column);
            VortexTable.ZoneSum zoneSum = table.zoneSum(column);
            return new ColumnFold(zoneSum.sum(), stats.stats().min(), stats.stats().max(),
                    stats.stats().nullCount(), stats.totalRows());
        }
        return table.filteredFold(filter, column)
                .map(f -> new ColumnFold(f.sum(), f.min(), f.max(), f.nullCount(), f.rows()))
                .orElse(null);
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
    private static String resolveColumn(int aggInput, List<String> scanColumns, Project project) {
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

    /// Builds a literal for a non-null `MIN`/`MAX` value, supporting only numeric output types
    /// (exact and approximate). A non-numeric value yields `null` so the rule abandons the rewrite.
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
