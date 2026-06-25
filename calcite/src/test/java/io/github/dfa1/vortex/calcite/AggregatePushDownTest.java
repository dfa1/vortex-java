package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.reader.VortexReader;

import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/// Phase 2: proves [VortexAggregatePushDownRule] rewrites a whole-table `MIN`/`MAX`/`COUNT` query
/// into a single-row `Values` computed from zone-map statistics — no table scan in the final plan.
class AggregatePushDownTest {

    private static final int ROWS = 200_000;
    private static final int CHUNK = 10_000;

    @TempDir
    static Path tmp;
    private static Path file;

    @BeforeAll
    static void writeFile() throws Exception {
        file = tmp.resolve("ohlc.vortex");
        OhlcGenerator.write(file, ROWS, CHUNK);
    }

    @Test
    void minMaxCountRewriteToValuesFromStats() throws Exception {
        // Given a planner over the OHLC schema and a whole-table MIN/MAX/COUNT query
        SchemaPlus root = Frameworks.createRootSchema(true);
        SchemaPlus vtx = root.add("vtx", new VortexSchema(Map.of("ohlc", file)));
        FrameworkConfig config = Frameworks.newConfigBuilder()
                .defaultSchema(vtx)
                // Preserve identifier case so unquoted `ohlc` matches the registered table name.
                .parserConfig(org.apache.calcite.sql.parser.SqlParser.config()
                        .withUnquotedCasing(org.apache.calcite.avatica.util.Casing.UNCHANGED))
                .build();
        Planner planner = Frameworks.getPlanner(config);
        SqlNode parsed = planner.parse("select min(low), max(high), count(*) from ohlc");
        RelNode logical = planner.rel(planner.validate(parsed)).rel;

        // When the aggregate push-down rule runs
        HepProgram program = new HepProgramBuilder()
                .addRuleCollection(VortexAggregatePushDownRule.RULES)
                .build();
        HepPlanner hep = new HepPlanner(program);
        hep.setRoot(logical);
        RelNode optimized = hep.findBestExp();

        String plan = RelOptUtil.toString(optimized);
        System.out.println();
        System.out.println("Aggregate push-down — optimized plan:");
        plan.lines().forEach(l -> System.out.println("  " + l));

        // Then the plan is a single-row Values with no scan or aggregate left
        assertThat(plan).contains("LogicalValues").doesNotContain("TableScan").doesNotContain("Aggregate");

        Values values = findValues(optimized);
        assertThat(values).isNotNull();
        List<RexLiteral> ohlcRow = values.getTuples().getFirst();

        // And the literal values equal what the stats say (no data was decoded to produce them)
        try (VortexReader reader = VortexReader.open(file)) {
            VortexAggregates.Summary low = VortexAggregates.of(reader, "low");
            VortexAggregates.Summary high = VortexAggregates.of(reader, "high");
            assertThat(ohlcRow.get(0).getValueAs(Double.class)).isEqualTo(((Number) low.min()).doubleValue());
            assertThat(ohlcRow.get(1).getValueAs(Double.class)).isEqualTo(((Number) high.max()).doubleValue());
            assertThat(ohlcRow.get(2).getValueAs(Long.class)).isEqualTo((long) ROWS);
        }
    }

    @Test
    @SuppressWarnings("try") // the Hook.Closeable is used only for its scope (deregister on close)
    void pushDownRunsEndToEndThroughJdbcPlanner() throws Exception {
        // Given a JDBC Calcite connection over the OHLC schema with the rule registered on the
        // (Volcano) planner via Hook.PLANNER
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");
        String sql = "select min(low) lo, max(high) hi, count(*) c from vtx.ohlc";

        try (Hook.Closeable ignored = Hook.PLANNER.addThread(
                (Consumer<RelOptPlanner>) planner -> VortexAggregatePushDownRule.RULES.forEach(planner::addRule));
             Connection conn = DriverManager.getConnection("jdbc:calcite:", info)) {
            conn.unwrap(CalciteConnection.class).getRootSchema()
                    .add("vtx", new VortexSchema(Map.of("ohlc", file)));

            // When EXPLAIN is run, the chosen plan answers from stats — a Values, no scan/aggregate
            String plan;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("explain plan for " + sql)) {
                rs.next();
                plan = rs.getString(1);
            }
            System.out.println();
            System.out.println("JDBC aggregate push-down — chosen plan:");
            plan.lines().forEach(l -> System.out.println("  " + l));

            // And the query executes, producing the stats-derived row
            double lo;
            double hi;
            long c;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                lo = rs.getDouble("lo");
                hi = rs.getDouble("hi");
                c = rs.getLong("c");
            }

            // Then the plan touched no table scan and the values match the zone-map stats
            assertThat(plan).containsIgnoringCase("Values").doesNotContain("TableScan").doesNotContain("Aggregate");
            try (VortexReader reader = VortexReader.open(file)) {
                assertThat(lo).isEqualTo(((Number) VortexAggregates.of(reader, "low").min()).doubleValue());
                assertThat(hi).isEqualTo(((Number) VortexAggregates.of(reader, "high").max()).doubleValue());
            }
            assertThat(c).isEqualTo(ROWS);
        }
    }

    @Test
    @SuppressWarnings("try") // the Hook.Closeable is used only for its scope (deregister on close)
    void filteredAggregateIsNotAnsweredFromWholeTableStats() throws Exception {
        // Given the rule on the JDBC (Volcano) planner and a WHERE that pushes a predicate into the
        // scan — whole-table stats must not be used to answer a filtered aggregate
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");
        try (Hook.Closeable ignored = Hook.PLANNER.addThread(
                (Consumer<RelOptPlanner>) planner -> VortexAggregatePushDownRule.RULES.forEach(planner::addRule));
             Connection conn = DriverManager.getConnection("jdbc:calcite:", info)) {
            conn.unwrap(CalciteConnection.class).getRootSchema()
                    .add("vtx", new VortexSchema(Map.of("ohlc", file)));

            // When MIN(low) is taken over only the rows with low > 10
            double min;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("select min(low) lo from vtx.ohlc where low > 10")) {
                rs.next();
                min = rs.getDouble("lo");
            }

            // Then it is the filtered minimum (> 10), not the whole-table MIN (≈ 0.x) the rule would
            // wrongly return if it ignored the pushed-down filter
            assertThat(min).isGreaterThan(10.0);
        }
    }

    // Note: the absent-MIN/MAX-stat and absent-NULL_COUNT guards in VortexAggregatePushDownRule are
    // defensive for files whose writer omits per-column stats (e.g. some Rust-written columns or
    // future stat-less encodings). The in-house Java writer always emits min/max/null_count for
    // primitive columns (verified), so those abandon paths cannot be exercised from this module.

    private static Values findValues(RelNode node) {
        if (node instanceof Values v) {
            return v;
        }
        for (RelNode input : node.getInputs()) {
            Values found = findValues(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
