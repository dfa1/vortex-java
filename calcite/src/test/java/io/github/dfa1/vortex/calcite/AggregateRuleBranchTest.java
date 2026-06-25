package io.github.dfa1.vortex.calcite;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Branch coverage for [VortexAggregatePushDownRule]: each query either rewrites to a single-row
/// `LogicalValues` (answerable from zone-map stats) or is left with its `Aggregate`/`TableScan`
/// intact (the rule must abandon — wrong stats would give a wrong answer).
class AggregateRuleBranchTest {

    private static final int ROWS = 30_000;
    private static final int CHUNK = 10_000;

    @TempDir
    static Path tmp;
    private static SchemaPlus schema;

    @BeforeAll
    static void writeFile() throws Exception {
        Path file = tmp.resolve("ohlc.vortex");
        OhlcGenerator.write(file, ROWS, CHUNK);
        SchemaPlus root = Frameworks.createRootSchema(true);
        schema = root.add("vtx", new VortexSchema(Map.of("ohlc", file)));
    }

    @Test
    void countStar_noProjectPath_rewritesToValues() {
        // Given a bare COUNT(*) — Aggregate(TableScan), the NO_PROJECT operand with project == null
        // When / Then — answered from the footer row count
        assertThat(optimize("select count(*) from ohlc")).contains("LogicalValues").doesNotContain("Aggregate");
    }

    @Test
    void countColumn_withProjectPath_rewritesToValues() {
        // Given COUNT(volume) — Aggregate(Project(TableScan)); COUNT(col) = rows − nulls from stats
        // When / Then
        assertThat(optimize("select count(volume) from ohlc")).contains("LogicalValues").doesNotContain("Aggregate");
    }

    @Test
    void sum_hasNoZoneStat_abandonsRewrite() {
        // Given SUM(volume) — no SUM zone statistic exists, so evaluate() yields null and the whole
        // rewrite is abandoned
        // When / Then — the Aggregate survives for the normal scan path
        assertThat(optimize("select sum(volume) from ohlc")).contains("Aggregate");
    }

    @Test
    void minOnNonNumericColumn_abandonsRewrite() {
        // Given MIN(symbol) over a VARCHAR column — the stat value is non-numeric, numericLiteral
        // returns null, the rewrite is abandoned
        // When / Then
        assertThat(optimize("select min(symbol) from ohlc")).contains("Aggregate");
    }

    @Test
    void minOverComputedExpression_abandonsRewrite() {
        // Given MIN(low + 1) — the projected input is an expression, not a bare column ref, so
        // resolveColumn returns null and the rewrite is abandoned
        // When / Then
        assertThat(optimize("select min(low + 1) from ohlc")).contains("Aggregate");
    }

    @Test
    void groupedAggregate_isLeftUntouched() {
        // Given a GROUP BY — group count != 0, the rule returns immediately
        // When / Then — Aggregate stays
        assertThat(optimize("select symbol, max(high) from ohlc group by symbol")).contains("Aggregate");
    }

    private static String optimize(String sql) {
        FrameworkConfig config = Frameworks.newConfigBuilder()
                .defaultSchema(schema)
                .parserConfig(SqlParser.config().withUnquotedCasing(Casing.UNCHANGED))
                .build();
        Planner planner = Frameworks.getPlanner(config);
        try {
            SqlNode parsed = planner.parse(sql);
            RelNode logical = planner.rel(planner.validate(parsed)).rel;
            HepProgram program = new HepProgramBuilder()
                    .addRuleCollection(VortexAggregatePushDownRule.RULES)
                    .build();
            HepPlanner hep = new HepPlanner(program);
            hep.setRoot(logical);
            return RelOptUtil.toString(hep.findBestExp());
        } catch (Exception e) {
            throw new IllegalStateException("planning failed for: " + sql, e);
        }
    }
}
