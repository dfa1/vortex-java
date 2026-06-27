package io.github.dfa1.vortex.calcite;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.rules.CoreRules;

import java.util.List;

/// The logical scan a [VortexTable] translates to. Its sole purpose over the stock
/// [LogicalTableScan] is the [#register(RelOptPlanner)] hook: when the planner first sees this
/// node class it installs the Vortex aggregate push-down rules, so a plain `jdbc:calcite:`
/// connection rewrites whole-table aggregates from zone-map statistics **without the caller wiring
/// the rule onto the planner** (ADR 0018 Phase 2 productionisation).
///
/// The node carries no projection or filter of its own. A `VortexExpandTableScanRule` immediately
/// rewrites it to a [LogicalTableScan], so Calcite's stock `Bindables` rules drive column
/// projection and `RowFilter` chunk-skip push-down exactly as before — this class only adds the
/// rule-registration seam, it does not change the scan/execute path.
final class VortexTableScan extends TableScan {

    VortexTableScan(RelOptCluster cluster, RelTraitSet traitSet, RelOptTable table) {
        super(cluster, traitSet, List.of(), table);
    }

    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
        assert inputs.isEmpty() : "VortexTableScan has no inputs";
        return new VortexTableScan(getCluster(), traitSet, table);
    }

    /// Called once by the planner the first time a node of this class is registered. Installs the
    /// whole-table aggregate push-down rules, the `AVG → SUM/COUNT` reduction that lets `AVG` ride
    /// that path, and the rule that expands this node to a stock [LogicalTableScan].
    ///
    /// @param planner the planner registering this node class
    @Override
    public void register(RelOptPlanner planner) {
        VortexAggregatePushDownRule.RULES.forEach(planner::addRule);
        // Reduce AVG (and STDDEV/VAR) to SUM/COUNT so the aggregate rule can answer AVG from the
        // per-zone SUM + row/null counts instead of streaming the column.
        planner.addRule(CoreRules.AGGREGATE_REDUCE_FUNCTIONS);
        planner.addRule(VortexExpandTableScanRule.INSTANCE);
    }

    /// Rewrites a [VortexTableScan] to a stock [LogicalTableScan], handing the scan back to
    /// Calcite's `Bindables` convention for projection / filter push-down and execution. Without
    /// this expansion the `Convention.NONE` [VortexTableScan] has no physical implementation.
    // Same justification as VortexAggregatePushDownRule: the classic operand() constructor is
    // deprecated but far lighter than the Immutables-based RelRule.Config for a one-line rule.
    @SuppressWarnings("deprecation")
    static final class VortexExpandTableScanRule extends RelOptRule {

        static final VortexExpandTableScanRule INSTANCE = new VortexExpandTableScanRule();

        private VortexExpandTableScanRule() {
            super(operand(VortexTableScan.class, any()), "VortexExpandTableScanRule");
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            VortexTableScan scan = call.rel(0);
            call.transformTo(LogicalTableScan.create(
                    scan.getCluster(), scan.getTable(), scan.getHints()));
        }
    }

    static VortexTableScan create(RelOptCluster cluster, RelOptTable table) {
        return new VortexTableScan(cluster, cluster.traitSetOf(Convention.NONE), table);
    }
}
