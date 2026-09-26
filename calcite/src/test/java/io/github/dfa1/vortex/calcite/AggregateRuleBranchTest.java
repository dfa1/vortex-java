package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.MemorySize;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

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

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        // A second, dedicated file for the VARCHAR MIN/MAX test below: the shared OHLC fixture's
        // "symbol" column writes with globalDict=true, which — a separate, pre-existing gap this
        // test must not depend on — carries no zone-map min/max at all, so a globalDict-encoded
        // Utf8 column always abandons regardless of the Calcite-side fix. `strings` disables it.
        Path stringsFile = tmp.resolve("strings.vortex");
        DType.Struct stringsSchema = DType.structBuilder().field("symbol", DType.UTF8).build();
        WriteOptions stringsOpts = new WriteOptions(4, true, 0.90, 0, false, false, MemorySize.ofMiB(256), Map.of());
        try (var ch = FileChannel.open(stringsFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, stringsSchema, stringsOpts)) {
            writer.writeChunk(Map.of(ColumnName.of("symbol"), new String[]{"AAPL", "MSFT", "NVDA", "TSLA"}));
        }
        SchemaPlus root = Frameworks.createRootSchema(true);
        schema = root.add("vtx", new VortexSchema(Map.of("ohlc", file, "strings", stringsFile)));
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
    void sum_withZoneStat_rewritesToValues() {
        // Given SUM(volume) — the Java writer emits a per-zone SUM stat, so ZoneReducer folds every
        // zone metadata-only and the whole rewrite succeeds
        // When / Then — answered from the zone-map table, no Aggregate/TableScan left
        assertThat(optimize("select sum(volume) from ohlc")).contains("LogicalValues").doesNotContain("Aggregate");
    }

    @Test
    void sumOverComputedExpression_abandonsRewrite() {
        // Given SUM(volume + 1) — the projected input is an expression, not a bare column ref, so
        // resolveColumn returns null and the SUM branch abandons
        // When / Then — the Aggregate survives for the normal scan path
        assertThat(optimize("select sum(volume + 1) from ohlc")).contains("Aggregate");
    }

    @Test
    void minMaxOnVarcharColumn_rewritesToValues() {
        // Given MIN/MAX(symbol) over a VARCHAR column with a real zone-map min/max (issue #406
        // gap 4) — minMaxLiteral wraps the String stat as an NlsString literal instead of abandoning
        // When / Then
        assertThat(optimize("select min(symbol), max(symbol) from strings"))
                .contains("LogicalValues").doesNotContain("Aggregate");
    }

    @Test
    void minOnDateColumn_abandonsRewrite() {
        // Given MIN("date") over a DATE column ("date" is a reserved word, needs quoting here since
        // this planner isn't wired with the Babel parser) — the stat value IS a Number (days since
        // epoch), but DATE isn't in minMaxLiteral's/numericLiteral's supported SqlTypeName sets
        // (neither the CHAR family nor the exact/approximate numeric families), so it still abandons
        // When / Then
        assertThat(optimize("select min(\"date\") from ohlc")).contains("Aggregate");
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
