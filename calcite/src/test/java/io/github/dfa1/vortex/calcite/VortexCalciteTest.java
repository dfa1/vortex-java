package io.github.dfa1.vortex.calcite;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/// Covers [VortexCalcite#connect(String, Map)] — the one-call JDBC entry point: it registers the
/// schema, the aggregate push-down rule auto-fires through it, and a reserved-word column is
/// queryable when back-tick quoted.
class VortexCalciteTest {

    @TempDir
    static Path tmp;
    private static Path file;

    @BeforeAll
    static void writeFile() throws Exception {
        file = tmp.resolve("ohlc.vortex");
        // Small two-zone file; the OHLC schema includes reserved-word columns (date, open, close).
        OhlcGenerator.write(file, 20_000, 10_000);
    }

    @Test
    void connect_registersSchemaAndPushesAggregateWithNoScan() throws Exception {
        // Given a connection opened through the helper (no manual DriverManager/unwrap/add)
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("ohlc", file))) {

            // When a whole-table SUM is explained — the rule must auto-register via VortexTableScan
            String plan;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("explain plan for select sum(volume) from vtx.ohlc")) {
                rs.next();
                plan = rs.getString(1);
            }

            // Then it is answered from zone-map stats — no data segment decoded
            assertThat(plan).doesNotContain("TableScan");
        }
    }

    @Test
    void connect_reservedWordColumnsAreQueryableUnquoted() throws Exception {
        // Given the OHLC table has columns named close and open — both SQL reserved words (CLOSE /
        // OPEN cursor) that the stock parser rejects unquoted
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("ohlc", file))) {

            // When they are selected UNQUOTED — the Babel parser accepts reserved words as identifiers
            long rows = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("select close, open from vtx.ohlc")) {
                while (rs.next()) {
                    rows++;
                }
            }

            // Then the query runs and returns every row, no back-ticks needed
            assertThat(rows).isEqualTo(20_000);
        }
    }

    @Test
    void connect_typeKeywordColumnStillNeedsQuoting() throws Exception {
        // Given the `date` column: even Babel keeps DATE reserved (it begins a date literal,
        // DATE '2020-01-01'), so it is the residual case that still needs back-ticks
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("ohlc", file))) {

            // When `date` is back-tick quoted
            long rows = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("select `date` from vtx.ohlc")) {
                while (rs.next()) {
                    rows++;
                }
            }

            // Then it resolves to the column and returns every row
            assertThat(rows).isEqualTo(20_000);
        }
    }

    @Test
    void connect_nullArguments_throw() {
        // Given / When / Then — both arguments are required
        assertThatNullPointerException().isThrownBy(() -> VortexCalcite.connect(null, Map.of()));
        assertThatNullPointerException().isThrownBy(() -> VortexCalcite.connect("vtx", null));
    }
}
