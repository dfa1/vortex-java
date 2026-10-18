package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.reader.RowFilter;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.interpreter.Bindables;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.NlsString;
import org.apache.calcite.util.TimeString;
import org.apache.calcite.util.TimestampString;
import org.apache.calcite.util.Util;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Rewrites `ORDER BY ... LIMIT n` over a [VortexTable] into a [LogicalValues] holding the first
/// `n` rows, found by streaming the table through a bounded heap
/// ([VortexTable#topN(int[], RowFilter, List, int)]) instead of letting Calcite sort every row:
/// Calcite's sort compares numbers by converting both to `BigDecimal`, which dominated a 4M-row
/// `ORDER BY ... DESC LIMIT 10`.
///
/// Fires for a `LIMIT` of at most [#MAX_ROWS] (with any `OFFSET`) over the table, optionally
/// through a projection of plain columns and a `WHERE` the filter translation captures in full.
/// It leaves the plan alone when a value has no literal of its column's type (`NaN` or an infinity,
/// a string outside the type's charset, a binary, `UUID` or nested value).
// Same justification as VortexAggregatePushDownRule: the classic operand() constructor is
// deprecated but far lighter than the Immutables-based RelRule.Config for an adapter rule.
@SuppressWarnings("deprecation")
public final class VortexSortPushDownRule extends RelOptRule {

    /// Most rows a rewrite emits; each becomes a literal row of the plan.
    private static final int MAX_ROWS = 1000;

    /// Most literal cells (rows times columns) a push-down may put in one `VALUES`. Calcite compiles
    /// the whole `VALUES` into a single Java method, which the JVM caps at 64 KB of bytecode: measured
    /// on a mixed-type table, 4,500 cells compile and 6,000 do not ("Code grows beyond 64 KB"). A bigger
    /// result is left to Calcite's own scan and sort, which are slower but correct.
    static final int MAX_VALUES_CELLS = 4_000;

    /// Every rule variant, for registering with a planner in one call.
    public static final List<RelOptRule> RULES = List.of(
            new VortexSortPushDownRule(operand(Sort.class, operand(Project.class,
                    operand(Filter.class, operand(TableScan.class, none())))), "VortexSortPushDownRule:project-filter"),
            new VortexSortPushDownRule(operand(Sort.class, operand(Project.class,
                    operand(TableScan.class, none()))), "VortexSortPushDownRule:project"),
            new VortexSortPushDownRule(operand(Sort.class, operand(Filter.class,
                    operand(TableScan.class, none()))), "VortexSortPushDownRule:filter"),
            new VortexSortPushDownRule(operand(Sort.class, operand(TableScan.class, none())),
                    "VortexSortPushDownRule:scan"));

    private VortexSortPushDownRule(RelOptRuleOperand operand, String description) {
        super(operand, description);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        Sort sort = call.rel(0);
        if (sort.fetch == null || sort.getCollation().getFieldCollations().isEmpty()) {
            return; // a full ORDER BY must emit every row
        }
        int fetch = intLiteral(sort.fetch);
        int offset = sort.offset == null ? 0 : intLiteral(sort.offset);
        if (fetch < 0 || offset < 0 || fetch + (long) offset > MAX_ROWS) {
            return;
        }
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
        List<RexNode> predicates = new ArrayList<>();
        if (filter != null) {
            predicates.add(filter.getCondition());
        }
        if (scan instanceof Bindables.BindableTableScan bindable) {
            predicates.addAll(bindable.filters);
        }
        RowFilter rowFilter = null;
        if (!predicates.isEmpty()) {
            Optional<RowFilter> translated = table.translatePushedFilters(predicates);
            if (translated.isEmpty()) {
                return; // a row Calcite would drop must not reach the top N
            }
            rowFilter = translated.get();
        }
        int[] cols = columns(scan, project);
        if (cols == null || (fetch + (long) offset) * cols.length > MAX_VALUES_CELLS) {
            return;
        }
        List<VortexTable.SortKey> keys = new ArrayList<>();
        for (RelFieldCollation collation : sort.getCollation().getFieldCollations()) {
            boolean descending = collation.getDirection().isDescending();
            RelFieldCollation.NullDirection nulls = collation.nullDirection == RelFieldCollation.NullDirection.UNSPECIFIED
                    ? collation.getDirection().defaultNullDirection() : collation.nullDirection;
            keys.add(new VortexTable.SortKey(collation.getFieldIndex(), descending,
                    nulls == RelFieldCollation.NullDirection.FIRST));
        }
        int count = offset + fetch;
        RowFilter finalFilter = rowFilter;
        List<Object> foldKey = List.of("topN", table, java.util.Arrays.toString(cols),
                rowFilter == null ? List.of() : rowFilter, keys, count);
        List<Object[]> rows = VortexAggregatePushDownRule.memo(call.getPlanner(), foldKey,
                () -> table.topN(cols, finalFilter, keys, count));

        RexBuilder rexBuilder = sort.getCluster().getRexBuilder();
        List<RelDataType> types = sort.getRowType().getFieldList().stream().map(f -> f.getType()).toList();
        List<ImmutableList<RexLiteral>> tuples = new ArrayList<>();
        for (Object[] row : rows.subList(Math.min(offset, rows.size()), rows.size())) {
            List<RexLiteral> literals = new ArrayList<>();
            for (int i = 0; i < row.length; i++) {
                RexLiteral literal = literal(rexBuilder, row[i], types.get(i));
                if (literal == null) {
                    return;
                }
                literals.add(literal);
            }
            tuples.add(ImmutableList.copyOf(literals));
        }
        call.transformTo(LogicalValues.create(sort.getCluster(), sort.getRowType(), ImmutableList.copyOf(tuples)));
    }

    /// The table columns the sort's input emits, in order, or `null` when a projected expression is
    /// not a plain column.
    private static int[] columns(TableScan scan, Project project) {
        // a scan that already pushed a projection emits only those columns, in that order
        int[] base;
        if (scan instanceof Bindables.BindableTableScan bindable && !bindable.projects.isEmpty()) {
            base = bindable.projects.stream().mapToInt(Integer::intValue).toArray();
        } else {
            base = new int[scan.getRowType().getFieldCount()];
            for (int i = 0; i < base.length; i++) {
                base[i] = i;
            }
        }
        if (project == null) {
            return base;
        }
        List<RexNode> projects = project.getProjects();
        int[] cols = new int[projects.size()];
        for (int i = 0; i < cols.length; i++) {
            if (!(projects.get(i) instanceof RexInputRef ref)) {
                return null;
            }
            cols[i] = base[ref.getIndex()];
        }
        return cols;
    }

    private static int intLiteral(RexNode node) {
        return node instanceof RexLiteral literal && literal.getValueAs(Integer.class) instanceof Integer value
                ? value : -1;
    }

    /// A literal of `type` for a value in the scan's form (DATE as epoch days, TIME as millis of
    /// day, TIMESTAMP as epoch millis), or `null` when the type has no literal for it.
    static RexLiteral literal(RexBuilder rexBuilder, Object value, RelDataType type) {
        if (value == null) {
            return rexBuilder.makeNullLiteral(type);
        }
        return switch (type.getSqlTypeName()) {
            case TINYINT, SMALLINT, INTEGER, BIGINT ->
                    rexBuilder.makeExactLiteral(BigDecimal.valueOf(((Number) value).longValue()), type);
            case DECIMAL -> rexBuilder.makeExactLiteral((BigDecimal) value, type);
            case REAL, FLOAT, DOUBLE -> {
                double d = ((Number) value).doubleValue();
                yield Double.isFinite(d) ? rexBuilder.makeApproxLiteral(new BigDecimal(value.toString()), type) : null;
            }
            case BOOLEAN -> rexBuilder.makeLiteral((Boolean) value, type);
            case CHAR, VARCHAR -> {
                Charset charset = type.getCharset() != null ? type.getCharset() : Util.getDefaultCharset();
                yield charset.newEncoder().canEncode((String) value)
                        ? rexBuilder.makeLiteral(new NlsString((String) value, null, null), type) : null;
            }
            case DATE -> rexBuilder.makeLiteral(DateString.fromDaysSinceEpoch((Integer) value), type);
            case TIME -> rexBuilder.makeLiteral(TimeString.fromMillisOfDay((Integer) value), type);
            case TIMESTAMP -> rexBuilder.makeLiteral(TimestampString.fromMillisSinceEpoch((Long) value), type);
            default -> null;
        };
    }
}
