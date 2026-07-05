package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Null-aware `SUM` push-down for [VortexAggregatePushDownRule]: the per-zone SUM of an all-null
/// zone is recorded as a sum-neutral 0, so a naive fold would answer `SUM` over an all-null column
/// as `0` instead of the SQL `NULL`. These tests pin the null-count guard that distinguishes the
/// two — and prove a *genuine* zero sum (no nulls) still pushes down to the literal `0`.
class AggregateSumNullTest {

    @TempDir
    Path tmp;

    /// Single nullable `I64` column `v`, written as one chunk (one zone) so the file carries exactly
    /// one per-zone SUM row. `values` holds the raw longs; `valid[i] == false` marks row `i` null.
    private SchemaPlus tableOf(long[] values, boolean[] valid) throws IOException {
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("v")), List.of(new DType.Primitive(PType.I64, true)), false);
        Path file = tmp.resolve("sum-nulls.vortex");
        // Large chunk so the whole column is one chunk; zone maps on so the SUM stat is emitted.
        WriteOptions opts = new WriteOptions(1024, true, 0.90, 0, false, false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema, opts)) {
            writer.writeChunk(Map.of("v", new NullableData(values, valid)));
        }
        SchemaPlus root = Frameworks.createRootSchema(true);
        return root.add("vtx", new VortexSchema(Map.of("t", file)));
    }

    @Test
    void sumOverAllNullColumn_rewritesToNullNotZero() throws Exception {
        // Given a column whose every row is null — SQL SUM is NULL, yet the writer records the zone
        // sum as a sum-neutral 0; the null-count guard must override the 0 fold
        SchemaPlus schema = tableOf(new long[]{0, 0, 0}, new boolean[]{false, false, false});

        // When the rule runs over SUM(v)
        Values values = pushDown(schema, "select sum(v) from t");

        // Then it answers from stats (a single-row Values, no scan) but the value is SQL NULL, not 0
        assertThat(values).isNotNull();
        RexLiteral sum = values.getTuples().getFirst().getFirst();
        assertThat(sum.isNull()).isTrue();
    }

    @Test
    void sumOverGenuineZero_rewritesToZeroLiteral() throws Exception {
        // Given a column with no nulls whose values cancel to 0 (1 + -1) — the fold is 0 but the
        // null count is 0, so the guard must NOT mistake it for all-null; SUM is the literal 0
        SchemaPlus schema = tableOf(new long[]{1, -1}, new boolean[]{true, true});

        // When the rule runs over SUM(v)
        Values values = pushDown(schema, "select sum(v) from t");

        // Then the rewrite succeeds with an exact 0, not NULL and not abandoned
        assertThat(values).isNotNull();
        RexLiteral sum = values.getTuples().getFirst().getFirst();
        assertThat(sum.isNull()).isFalse();
        assertThat(sum.getValueAs(Long.class)).isZero();
    }

    /// Optimizes `sql` with the push-down rules and returns the single-row [Values] the rewrite
    /// produced, asserting no scan or aggregate survived. Fails the test if the rule abandoned.
    private static Values pushDown(SchemaPlus schema, String sql) throws Exception {
        FrameworkConfig config = Frameworks.newConfigBuilder()
                .defaultSchema(schema)
                .parserConfig(SqlParser.config().withUnquotedCasing(Casing.UNCHANGED))
                .build();
        Planner planner = Frameworks.getPlanner(config);
        SqlNode parsed = planner.parse(sql);
        RelNode logical = planner.rel(planner.validate(parsed)).rel;
        HepProgram program = new HepProgramBuilder()
                .addRuleCollection(VortexAggregatePushDownRule.RULES)
                .build();
        HepPlanner hep = new HepPlanner(program);
        hep.setRoot(logical);
        RelNode optimized = hep.findBestExp();

        String plan = RelOptUtil.toString(optimized);
        assertThat(plan).contains("LogicalValues").doesNotContain("TableScan").doesNotContain("Aggregate");
        return findValues(optimized);
    }

    private static Values findValues(RelNode node) {
        if (node instanceof Values values) {
            return values;
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
