package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Filtered aggregates over Rust's `vortex.zoned` zone map, the default since #447: fixed 8192-row
/// zones that do not line up with chunks. The fold required one zone per chunk, so on these files
/// every filtered aggregate fell back to a scan.
class ZonedAggregateFoldTest {

    private static final int ZONE = 8_192;
    // batches that straddle zone edges, so zones and chunks disagree
    private static final int BATCH = 5_000;
    private static final int ROWS = 12 * ZONE;

    @TempDir
    static Path tmp;
    private static Path file;

    @BeforeAll
    static void write() throws Exception {
        file = tmp.resolve("zoned.vortex");
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("val"), DType.I64)
                .field(ColumnName.of("s"), DType.UTF8)
                .build();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            for (int first = 0; first < ROWS; first += BATCH) {
                int n = Math.min(BATCH, ROWS - first);
                long[] id = new long[n];
                long[] val = new long[n];
                String[] s = new String[n];
                for (int i = 0; i < n; i++) {
                    id[i] = first + i;
                    val[i] = 3L * (first + i);
                    s[i] = String.format("k%06d", first + i);
                }
                writer.writeChunk(Map.of(ColumnName.of("id"), id, ColumnName.of("val"), val, ColumnName.of("s"), s));
            }
        }
    }

    @Test
    void countMinMax_foldFromZonesAndBoundaryChunks() throws Exception {
        // Given a range that covers several whole zones and cuts two of them
        long lo = ZONE + 100;
        long hi = 9L * ZONE + 300;
        String sql = "select count(*) c, count(val) cv, min(val) mn, max(val) mx from vtx.t where id between "
                + lo + " and " + hi;

        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file))) {
            // When
            String result = explain(conn, sql);

            // Then — answered without a scan, and the values match the data
            assertThat(result).containsIgnoringCase("Values").doesNotContain("TableScan");
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                assertThat(rs.getLong("c")).isEqualTo(hi - lo + 1);
                assertThat(rs.getLong("cv")).isEqualTo(hi - lo + 1);
                assertThat(rs.getLong("mn")).isEqualTo(3 * lo);
                assertThat(rs.getLong("mx")).isEqualTo(3 * hi);
            }
        }
    }

    @Test
    void sum_hasNoZoneStatistic_soItScans() throws Exception {
        // Given — Rust's zoned aggregate set records no sum, so a whole zone cannot be folded
        long lo = ZONE + 100;
        long hi = 9L * ZONE + 300;
        String sql = "select sum(val) from vtx.t where id between " + lo + " and " + hi;

        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file))) {
            // When
            String result = explain(conn, sql);

            // Then
            assertThat(result).contains("TableScan");
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(3 * (hi * (hi + 1) / 2 - (lo - 1) * lo / 2));
            }
        }
    }

    @Test
    void minOfAString_withoutAnExactZoneStatistic_isNotFoldedFromTheBoundaryAlone() throws Exception {
        // Given — a zoned string column records only truncated bounds, so whole zones carry no min;
        // folding only the boundary chunks would answer the min of the cut zone, not of the range
        long lo = ZONE + 100;
        long hi = 9L * ZONE + 300;
        String sql = "select min(s), max(s) from vtx.t where id between " + lo + " and " + hi;

        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             // When
             ResultSet result = st.executeQuery(sql)) {

            // Then
            result.next();
            assertThat(result.getString(1)).isEqualTo(String.format("k%06d", lo));
            assertThat(result.getString(2)).isEqualTo(String.format("k%06d", hi));
        }
    }

    private static String explain(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("explain plan for " + sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
