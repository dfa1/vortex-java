package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.reader.ArrayStats;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.interpreter.Bindables;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
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

/// Rewrites a whole-table `MIN`/`MAX`/`COUNT` aggregate over a [VortexTable] into a single-row
/// [LogicalValues] computed from the footer zone-map statistics — answering the query without
/// decoding a single data segment (ADR 0013 §6, ADR 0018 Phase 2).
///
/// Fires only when it can answer *every* aggregate from statistics: no `GROUP BY`, and each
/// call is `COUNT(*)`, `COUNT(col)`, `MIN(col)`, or `MAX(col)` over a numeric column. Anything
/// else (e.g. `SUM`, a grouped aggregate, `MIN` on a non-numeric column) leaves the plan
/// untouched for the normal scan path. `SUM`/`AVG` join this tier once the writer emits a
/// per-zone `SUM` statistic.
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

    /// Every rule variant, for registering with a planner in one call.
    public static final java.util.List<RelOptRule> RULES = java.util.List.of(WITH_PROJECT, NO_PROJECT);

    private VortexAggregatePushDownRule(RelOptRuleOperand operand, String description) {
        super(operand, description);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        Aggregate aggregate = call.rel(0);
        if (aggregate.getGroupCount() != 0) {
            return;
        }
        // Explicit operands give concrete rels under both Hep and Volcano: rel(1) is either the
        // Project (then rel(2) is the scan) or the scan directly.
        Project project;
        TableScan scan;
        if (call.rel(1) instanceof Project p) {
            project = p;
            scan = call.rel(2);
        } else {
            project = null;
            scan = call.rel(1);
        }
        VortexTable table = scan.getTable().unwrap(VortexTable.class);
        if (table == null) {
            return;
        }
        // Whole-table stats are only valid for a whole-table scan. If a WHERE predicate was pushed
        // into the scan (BindableTableScan.filters), answering from stats would ignore it and return
        // the wrong MIN/MAX/COUNT — leave the plan to compute it over the filtered rows.
        if (scan instanceof Bindables.BindableTableScan bindable && !bindable.filters.isEmpty()) {
            return;
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
                    project, rexBuilder);
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
    /// `null` if it cannot be answered (so the caller abandons the rewrite).
    private static RexLiteral evaluate(AggregateCall agg, RelDataType outType, VortexTable table,
                                       List<String> scanColumns, RelDataType scanRowType,
                                       Project project, RexBuilder rexBuilder) {
        return switch (agg.getAggregation().getKind()) {
            case COUNT -> {
                if (agg.getArgList().isEmpty()) {
                    yield exact(rexBuilder, table.totalRows(), outType);   // COUNT(*)
                }
                String col = resolveColumn(agg.getArgList().getFirst(), scanColumns, project);
                if (col == null) {
                    yield null;
                }
                Long nullCount = table.statsOf(col).nullCount();
                // COUNT(col) = rows − nulls. Without a NULL_COUNT stat we cannot assume zero nulls
                // for a nullable column (we would overcount), so abandon; a non-nullable column has
                // no nulls and is safe.
                if (nullCount == null && isNullable(scanRowType, col)) {
                    yield null;
                }
                long nulls = nullCount == null ? 0L : nullCount;
                yield exact(rexBuilder, table.totalRows() - nulls, outType);
            }
            case MIN, MAX -> {
                if (agg.getArgList().size() != 1) {
                    yield null;
                }
                String col = resolveColumn(agg.getArgList().getFirst(), scanColumns, project);
                if (col == null) {
                    yield null;
                }
                ArrayStats stats = table.statsOf(col);
                Object value = agg.getAggregation().getKind() == SqlKind.MIN ? stats.min() : stats.max();
                if (value == null) {
                    // No MIN/MAX stat. A genuine SQL NULL is only correct when the column provably has
                    // no non-null rows (empty table, or every row null); otherwise the stat is merely
                    // absent and we must abandon so the scan computes the real value.
                    long total = table.totalRows();
                    Long nullCount = stats.nullCount();
                    boolean provablyNoValues = total == 0 || (nullCount != null && nullCount == total);
                    yield provablyNoValues ? rexBuilder.makeNullLiteral(outType) : null;
                }
                yield numericLiteral(rexBuilder, value, outType);
            }
            default -> null;
        };
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
