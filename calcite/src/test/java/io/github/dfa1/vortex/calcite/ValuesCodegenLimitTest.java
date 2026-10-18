package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The sort and grouped push-downs answer with a literal `VALUES`, which Calcite compiles into one
/// Java method: past about 5,000 cells the JVM refuses it ("Code grows beyond 64 KB") and the query
/// fails outright. Past [VortexSortPushDownRule#MAX_VALUES_CELLS] the rules must leave the query to
/// Calcite's own scan, which returns the same rows; below it they must still push down.
class ValuesCodegenLimitTest {

    private static final int GROUPS = 3_000;

    @TempDir
    static Path tmp;
    private static Path file;

    /// 3,000 groups of 3 rows and six columns, so that `count(*)` per group is 6,000 cells and
    /// `ORDER BY ... LIMIT 800` is 4,800: both over the cap, yet each failed in the field at that size.
    @BeforeAll
    static void writeFile() throws Exception {
        file = tmp.resolve("wide.vortex");
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("k"), new DType.Utf8(false))
                .field(ColumnName.of("v"), DType.I64)
                .field(ColumnName.of("a"), new DType.Primitive(PType.F64, false))
                .field(ColumnName.of("b"), new DType.Primitive(PType.F64, false))
                .field(ColumnName.of("c"), new DType.Primitive(PType.F64, false))
                .field(ColumnName.of("d"), new DType.Primitive(PType.F64, false))
                .build();
        int rows = GROUPS * 3;
        String[] k = new String[rows];
        long[] v = new long[rows];
        double[] a = new double[rows];
        for (int i = 0; i < rows; i++) {
            k[i] = "key-" + (i % GROUPS);
            v[i] = i;
            a[i] = i * 0.5;
        }
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("k"), k, ColumnName.of("v"), v, ColumnName.of("a"), a,
                    ColumnName.of("b"), a, ColumnName.of("c"), a, ColumnName.of("d"), a));
        }
    }

    @Test
    void groupedOverTheCap_fallsBackToCalciteAndReturnsEveryGroup() throws Exception {
        // Given 3,000 groups x (key + count + sum) = 9,000 cells, which used to fail to compile
        String sql = "select k, count(*), sum(v) from vtx.t group by k";
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file))) {

            // When
            String plan = plan(conn, sql);
            long groups = 0;
            long total = 0;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    groups++;
                    total += rs.getLong(2);
                }
            }

            // Then Calcite scanned instead, and the answer is complete
            assertThat(plan).contains("TableScan").doesNotContain("Values");
            assertThat(groups).isEqualTo(GROUPS);
            assertThat(total).isEqualTo(GROUPS * 3L);
        }
    }

    @Test
    void groupedUnderTheCap_isStillPushedDown() throws Exception {
        // Given a filter leaving 300 groups x 3 cells = 900 cells, well under the cap
        String sql = "select k, count(*), sum(v) from vtx.t where v < 900 group by k";
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file))) {

            // When
            String plan = plan(conn, sql);

            // Then
            assertThat(plan).contains("Values").doesNotContain("TableScan");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {700, 800, 1000})
    void sortOverTheCap_fallsBackToCalciteAndReturnsTheTopRows(int limit) throws Exception {
        // Given 6 columns x 700+ rows > 4,000 cells
        String sql = "select * from vtx.t order by v limit " + limit;
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file))) {

            // When
            String plan = plan(conn, sql);
            long rows = 0;
            long last = -1;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    rows++;
                    last = rs.getLong("v");
                }
            }

            // Then
            assertThat(plan).doesNotContain("Values");
            assertThat(rows).isEqualTo(limit);
            assertThat(last).isEqualTo(limit - 1L);
        }
    }

    @Test
    void sortUnderTheCap_isStillPushedDown() throws Exception {
        // Given 6 columns x 600 rows = 3,600 cells
        String sql = "select * from vtx.t order by v limit 600";
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file))) {

            // When
            String plan = plan(conn, sql);

            // Then
            assertThat(plan).contains("Values");
        }
    }

    private static String plan(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("explain plan for " + sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
