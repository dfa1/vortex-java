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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// [VortexSortPushDownRule] answers `ORDER BY ... LIMIT n` from a bounded heap over the scan. Each
/// query is checked against the same query with the sort key wrapped in `CASE WHEN id > -1 THEN ...
/// END` (`id` is never null), which the rule cannot push, so Calcite sorts it: both must return the
/// same rows in the same order. A plainer wrapper such as `x + 0` does not work: Calcite simplifies
/// it back to `x` and pushes the oracle down too, so the test asserts the oracle really scans.
class SortPushDownTest {

    private static final int ROWS = 5_000;

    @TempDir
    static Path tmp;
    private static Path file;

    /// `id` is unique; `x` is unique where set and null on every 7th row; `s` cycles 5 symbols and
    /// is null on every 11th row; two chunks so the heap spans chunk boundaries.
    @BeforeAll
    static void writeFile() throws Exception {
        file = tmp.resolve("sort.vortex");
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("x"), new DType.Primitive(PType.F64, true))
                .field(ColumnName.of("s"), new DType.Utf8(true))
                .build();
        String[] symbols = {"delta", "alpha", "echo", "charlie", "bravo"};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            for (int part = 0; part < 2; part++) {
                int n = ROWS / 2;
                long[] id = new long[n];
                Double[] x = new Double[n];
                String[] s = new String[n];
                for (int i = 0; i < n; i++) {
                    long row = (long) part * n + i;
                    id[i] = row;
                    x[i] = row % 7 == 0 ? null : (row * 7919 % 10_007) / 3.0;
                    s[i] = row % 11 == 0 ? null : symbols[(int) (row % symbols.length)];
                }
                writer.writeChunk(Map.of(ColumnName.of("id"), id, ColumnName.of("x"), x, ColumnName.of("s"), s));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "order by x desc limit 10                         | order by case when id > -1 then x end desc limit 10",
            // Calcite puts nulls last ascending and first descending unless told otherwise
            "order by x limit 10                              | order by case when id > -1 then x end limit 10",
            "order by x desc nulls last limit 10              | order by case when id > -1 then x end desc nulls last limit 10",
            "order by x nulls first limit 10                  | order by case when id > -1 then x end nulls first limit 10",
            "order by s, id desc limit 12 offset 3            | order by case when id > -1 then s end, id desc limit 12 offset 3",
            "where s = 'echo' order by x desc limit 7         | where s = 'echo' order by case when id > -1 then x end desc limit 7",
            "where id < 50 order by id desc limit 100         | where id < 50 order by case when id > -1 then id end desc limit 100"})
    void orderByLimit_returnsTheRowsCalciteSortReturns(String tail, String oracleTail) throws Exception {
        // Given
        String sql = "select id, x, s from vtx.t " + tail;

        // When
        List<String> result = rows(sql);

        // Then — pushed down, and equal to Calcite's own sort
        String oracle = "select id, x, s from vtx.t " + oracleTail;
        assertThat(plan(sql)).contains("Values").doesNotContain("TableScan");
        assertThat(plan(oracle)).contains("TableScan");
        assertThat(result).isEqualTo(rows(oracle)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // a full sort must emit every row
            "select id from vtx.t order by x desc",
            // past the literal-row cap
            "select id from vtx.t order by x desc limit 5000",
            // a computed sort key
            "select id from vtx.t order by x + 1 desc limit 5"})
    void sortTheRuleCannotAnswer_isLeftToCalcite(String sql) throws Exception {
        // Given / When / Then
        assertThat(plan(sql)).contains("TableScan");
    }

    private static String plan(String sql) throws Exception {
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("explain plan for " + sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> rows(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Object[] row = new Object[columns];
                for (int c = 0; c < columns; c++) {
                    row[c] = rs.getObject(c + 1);
                }
                out.add(Arrays.toString(row));
            }
        }
        return out;
    }
}
