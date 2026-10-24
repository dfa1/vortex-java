package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// An ungrouped `COUNT(DISTINCT col)` is counted by [VortexTable#distinctCount(ColumnName,
/// io.github.dfa1.vortex.reader.ScanFilter)] instead of Calcite hashing a boxed value per row. Each
/// query must equal Calcite's own count over `CASE WHEN id > 0 THEN col END` (`id` is always positive),
/// which the rule leaves alone; the test asserts that oracle really scans.
class CountDistinctTest {

    @TempDir
    static Path tmp;
    private static Path file;

    /// Two chunks. `n` repeats values across chunks and has nulls; `x` holds -0.0, 0.0 and NaN,
    /// which Calcite's DISTINCT tells apart as `Double.equals` does; `s` repeats 3 symbols.
    @BeforeAll
    static void writeFile() throws Exception {
        file = tmp.resolve("distinct.vortex");
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("n"), DType.I64.asNullable())
                .field(ColumnName.of("x"), new DType.Primitive(PType.F64, false))
                .field(ColumnName.of("s"), new DType.Utf8(true))
                .build();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{1, 2, 3, 4, 5},
                    ColumnName.of("n"), new Long[]{10L, null, 10L, 20L, 30L},
                    ColumnName.of("x"), new double[]{-0.0, 0.0, Double.NaN, 1.5, -0.0},
                    ColumnName.of("s"), new String[]{"a", "b", null, "a", "c"}));
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{6, 7, 8},
                    ColumnName.of("n"), new Long[]{30L, 40L, null},
                    ColumnName.of("x"), new double[]{Double.NaN, 2.5, 1.5},
                    ColumnName.of("s"), new String[]{"c", "a", "d"}));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "count(distinct n)                  | count(distinct case when id > 0 then n end)",
            "count(distinct x)                  | count(distinct case when id > 0 then x end)",
            "count(distinct s)                  | count(distinct case when id > 0 then s end)",
            "count(distinct s), count(distinct n) | count(distinct case when id > 0 then s end), count(distinct case when id > 0 then n end)"})
    void countDistinct_isPushedDownAndEqualsCalcite(String aggregates, String oracle) throws Exception {
        // Given
        String sql = "select " + aggregates + " from vtx.t";

        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file))) {
            // When / Then
            assertThat(single(conn, "explain plan for " + sql)).contains("Values").doesNotContain("TableScan");
            assertThat(single(conn, "explain plan for select " + oracle + " from vtx.t")).contains("TableScan");
            assertThat(row(conn, sql)).isEqualTo(row(conn, "select " + oracle + " from vtx.t"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select s, count(distinct n) from vtx.t group by s",
            "select count(distinct n) filter (where id > 2) from vtx.t"})
    void distinctTheRuleDoesNotCount_isLeftToCalcite(String sql) throws Exception {
        // Given / When / Then
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file))) {
            assertThat(single(conn, "explain plan for " + sql)).contains("TableScan");
        }
    }

    private static String single(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String row(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            StringBuilder out = new StringBuilder();
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
                out.append(rs.getLong(c)).append('|');
            }
            return out.toString();
        }
    }
}
