package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// A decimal `SUM` (and the `AVG` reduced to it) is totalled as unscaled longs in the scan instead of
/// by Calcite with a `BigDecimal` per row. Each query must equal the same aggregate over
/// `CASE WHEN id > -1 THEN amount END` (`id` is never null), which the rules leave to Calcite; the test
/// asserts that oracle really scans.
class DecimalSumTest {

    @TempDir
    static Path tmp;
    private static Path file;

    /// Two chunks of DECIMAL(10, 2) amounts with nulls, negatives and a group column.
    @BeforeAll
    static void writeFile() throws Exception {
        file = tmp.resolve("decimal.vortex");
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("s"), DType.UTF8)
                .field(ColumnName.of("amount"), new DType.Decimal((byte) 10, (byte) 2, true))
                .build();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{1, 2, 3, 4},
                    ColumnName.of("s"), new String[]{"a", "b", "a", "b"},
                    ColumnName.of("amount"), new BigDecimal[]{new BigDecimal("12.34"), null,
                            new BigDecimal("-0.05"), new BigDecimal("9999999.99")}));
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{5, 6, 7},
                    ColumnName.of("s"), new String[]{"a", "c", "b"},
                    ColumnName.of("amount"), new BigDecimal[]{new BigDecimal("0.01"), null,
                            new BigDecimal("-12345.67")}));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "select sum(amount) from vtx.t "
                    + "| select sum(case when id > -1 then amount end) from vtx.t",
            "select sum(amount), avg(amount), count(amount) from vtx.t where id < 5 "
                    + "| select sum(case when id > -1 then amount end), avg(case when id > -1 then amount end), "
                    + "count(case when id > -1 then amount end) from vtx.t where id + 0 < 5",
            "select s, sum(amount), min(amount), max(amount) from vtx.t group by s order by s "
                    + "| select s, sum(case when id > -1 then amount end), min(case when id > -1 then amount end), "
                    + "max(case when id > -1 then amount end) from vtx.t group by s order by s"})
    void decimalAggregate_isPushedDownAndEqualsCalcite(String sql, String oracle) throws Exception {
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file))) {
            // When / Then
            assertThat(plan(conn, sql)).contains("Values").doesNotContain("TableScan");
            assertThat(plan(conn, oracle)).contains("TableScan");
            assertThat(rows(conn, sql)).isEqualTo(rows(conn, oracle)).isNotEmpty();
        }
    }

    private static String plan(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("explain plan for " + sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /// Rows as strings, decimals compared by value (`1.50` equals `1.5`).
    private static List<String> rows(Connection conn, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int c = 1; c <= columns; c++) {
                    Object value = rs.getObject(c);
                    row.append(value instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : value).append('|');
                }
                out.add(row.toString());
            }
        }
        return out;
    }
}
