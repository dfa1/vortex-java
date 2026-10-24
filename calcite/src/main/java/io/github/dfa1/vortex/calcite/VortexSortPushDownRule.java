package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.reader.ScanFilter;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.interpreter.Bindables;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.RelOptTableImpl;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalTableScan;
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
/// `n` rows, found by streaming the table through a bounded buffer
/// ([VortexTable#topN(int[], ScanFilter, List, int)]) instead of letting Calcite sort every row:
/// Calcite's sort compares numbers by converting both to `BigDecimal`, which dominated a 4M-row
/// `ORDER BY ... DESC LIMIT 10`. A `SELECT DISTINCT` (a `GROUP BY` with no aggregate) under the sort
/// is answered the same way, keeping only the first `n` distinct rows
/// ([VortexTable#distinctTopN(int[], ScanFilter, List, int)]) instead of hashing every row.
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

    /// Smallest table, in rows, whose `ORDER BY` with no `LIMIT` is rewritten to a lazy scan
    /// ([VortexOrderedTable]); a smaller one fits in memory and Calcite sorts it faster.
    static final int LAZY_SORT_MIN_ROWS = 100_000;

    /// Most literal cells (rows times columns) a push-down may put in one `VALUES`. Calcite compiles
    /// the whole `VALUES` into a single Java method, which the JVM caps at 64 KB of bytecode: measured
    /// on a mixed-type table, 4,500 cells compile and 6,000 do not ("Code grows beyond 64 KB"). A bigger
    /// result is left to Calcite's own scan and sort, which are slower but correct.
    static final int MAX_VALUES_CELLS = 4_000;

    /// Every rule variant, for registering with a planner in one call.
    public static final List<RelOptRule> RULES = List.of(
            rule("project-filter", Project.class, Filter.class),
            rule("project", Project.class),
            rule("filter", Filter.class),
            rule("scan"),
            rule("distinct-project-filter", Aggregate.class, Project.class, Filter.class),
            rule("distinct-project", Aggregate.class, Project.class),
            rule("distinct-filter", Aggregate.class, Filter.class),
            rule("distinct-scan", Aggregate.class));

    private VortexSortPushDownRule(RelOptRuleOperand operand, String description) {
        super(operand, description);
    }

    /// The rule matching a sort over `between`, outermost first, over a table scan.
    @SafeVarargs
    private static VortexSortPushDownRule rule(String name, Class<? extends RelNode>... between) {
        RelOptRuleOperand operand = operand(TableScan.class, none());
        for (int i = between.length - 1; i >= 0; i--) {
            operand = operand(between[i], operand);
        }
        return new VortexSortPushDownRule(operand(Sort.class, operand), "VortexSortPushDownRule:" + name);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        Sort sort = call.rel(0);
        if (sort.getCollation().getFieldCollations().isEmpty()) {
            return;
        }
        // no LIMIT: every row must come back sorted, which only a big table's lazy scan can do cheaply
        boolean lazy = sort.fetch == null;
        int fetch = 0;
        int offset = 0;
        if (lazy) {
            if (sort.offset != null) {
                return;
            }
        } else {
            fetch = intLiteral(sort.fetch);
            offset = sort.offset == null ? 0 : intLiteral(sort.offset);
            if (fetch < 0 || offset < 0 || fetch + (long) offset > MAX_ROWS) {
                return;
            }
        }
        Aggregate distinct = null;
        Project project = null;
        Filter filter = null;
        RelNode current = call.rel(1);
        int index = 1;
        if (current instanceof Aggregate a) {
            // SELECT DISTINCT, or a GROUP BY with no aggregate: rows come out once each
            if (lazy || !a.getAggCallList().isEmpty() || a.getGroupType() != Aggregate.Group.SIMPLE) {
                return;
            }
            distinct = a;
            current = call.rel(++index);
        }
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
        Double rowCount = table.getStatistic().getRowCount();
        if (lazy && (rowCount == null || rowCount <= LAZY_SORT_MIN_ROWS)) {
            return; // small enough for Calcite to sort in memory
        }
        List<RexNode> predicates = new ArrayList<>();
        if (filter != null) {
            predicates.add(filter.getCondition());
        }
        if (scan instanceof Bindables.BindableTableScan bindable) {
            predicates.addAll(bindable.filters);
        }
        ScanFilter rowFilter = null;
        if (!predicates.isEmpty()) {
            Optional<ScanFilter> translated = table.translatePushedFilters(predicates);
            if (translated.isEmpty()) {
                return; // a row Calcite would drop must not reach the top N
            }
            rowFilter = translated.get();
        }
        int[] cols = columns(scan, project);
        if (cols != null && distinct != null) {
            // the aggregate emits its group columns, in order
            int[] input = cols;
            cols = distinct.getGroupSet().asList().stream().mapToInt(i -> input[i]).toArray();
        }
        // only the rows after the offset become literals; the offset rows stay in the heap (MAX_ROWS)
        if (cols == null || (!lazy && (long) fetch * cols.length > MAX_VALUES_CELLS)) {
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
        if (lazy) {
            call.transformTo(orderedScan(sort, scan, new VortexOrderedTable(table, cols, rowFilter, keys,
                    sort.getRowType(), call.getMetadataQuery().getRowCount(sort.getInput()), sort.getCollation())));
            return;
        }
        int count = offset + fetch;
        ScanFilter finalFilter = rowFilter;
        int[] emitted = cols;
        boolean once = distinct != null;
        List<Object> foldKey = List.of(once ? "distinctTopN" : "topN", table, java.util.Arrays.toString(cols),
                rowFilter == null ? List.of() : rowFilter, keys, count);
        List<Object[]> rows = VortexAggregatePushDownRule.memo(call.getPlanner(), foldKey,
                () -> once ? table.distinctTopN(emitted, finalFilter, keys, count)
                        : table.topN(emitted, finalFilter, keys, count));

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

    /// A scan of `ordered` that Calcite plans like any table, in place of the sort and everything it
    /// sorted.
    private static RelNode orderedScan(Sort sort, TableScan scan, VortexOrderedTable ordered) {
        RelOptTable base = scan.getTable();
        RelOptTable derived = RelOptTableImpl.create(base.getRelOptSchema(), sort.getRowType(), ordered,
                ImmutableList.<String>builder().addAll(base.getQualifiedName()).add("ordered").build());
        return LogicalTableScan.create(sort.getCluster(), derived, scan.getHints());
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
