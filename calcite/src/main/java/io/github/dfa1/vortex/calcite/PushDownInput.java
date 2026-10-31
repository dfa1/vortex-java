package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.reader.ScanFilter;
import org.apache.calcite.interpreter.Bindables;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rex.RexNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// What a push-down rule's operand matched below its own node: an optional `Project` and `Filter`,
/// then the scan of a Vortex table, with every predicate on the way translated to one [ScanFilter].
///
/// @param project the projection above the scan, or `null` when the operand has none
/// @param scan    the table scan
/// @param table   the Vortex table the scan reads
/// @param filter  the predicates as one filter, or `null` when there are none
record PushDownInput(Project project, TableScan scan, VortexTable table, ScanFilter filter) {

    /// Walks the operand from `call.rel(index)` down to the scan. Explicit operands give concrete
    /// rels under both Hep and Volcano, and each rule variant fixes the shape, so the levels present
    /// are exactly those its operand declared.
    ///
    /// The predicates are the `Filter` node's condition at the logical level, and
    /// `BindableTableScan.filters` once folded into a physical scan. They translate strictly: a
    /// predicate the scan cannot evaluate abandons the rewrite, as a row Calcite would drop must not
    /// reach a pushed-down result.
    ///
    /// @param call  the rule call
    /// @param index the operand position just below the rule's own node
    /// @return the matched input, or empty when the scan is not a Vortex table or a predicate does
    ///         not translate
    static Optional<PushDownInput> match(RelOptRuleCall call, int index) {
        Project project = null;
        Filter filter = null;
        int next = index;
        RelNode current = call.rel(next);
        if (current instanceof Project p) {
            project = p;
            current = call.rel(++next);
        }
        if (current instanceof Filter f) {
            filter = f;
            current = call.rel(++next);
        }
        TableScan scan = (TableScan) current;
        VortexTable table = scan.getTable().unwrap(VortexTable.class);
        if (table == null) {
            return Optional.empty();
        }
        List<RexNode> predicates = new ArrayList<>();
        if (filter != null) {
            predicates.add(filter.getCondition());
        }
        if (scan instanceof Bindables.BindableTableScan bindable) {
            predicates.addAll(bindable.filters);
        }
        ScanFilter pushed = null;
        if (!predicates.isEmpty()) {
            Optional<ScanFilter> translated = table.translatePushedFilters(predicates);
            if (translated.isEmpty()) {
                return Optional.empty();
            }
            pushed = translated.get();
        }
        return Optional.of(new PushDownInput(project, scan, table, pushed));
    }
}
