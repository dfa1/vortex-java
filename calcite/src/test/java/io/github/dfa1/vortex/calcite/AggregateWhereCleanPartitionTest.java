package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.apache.calcite.jdbc.CalciteConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/// Proves the WHERE-filtered aggregate push-down (ADR 0018): when a pushed predicate partitions the
/// zones cleanly — every chunk fully selected or fully excluded, no boundary — `SUM`/`COUNT`/`MIN`/
/// `MAX` are folded from the kept zones' statistics with no data segment decoded, and the answer
/// matches the full-scan ground truth. When the predicate cuts through a chunk the rewrite is
/// abandoned and the scan computes the result.
///
/// The fixture is a clustered key `id` (monotone `0..ROWS`, one contiguous run per chunk) with
/// `val == id`. A predicate `id < k*CHUNK` then selects exactly the first `k` chunks, so its zone
/// boundary lands on a chunk boundary — the clean-partition case. `id < k*CHUNK + 1` cuts chunk `k`.
class AggregateWhereCleanPartitionTest {

    private static final int CHUNK = 1_000;
    private static final int CHUNKS = 8;
    private static final int ROWS = CHUNK * CHUNKS;

    @TempDir
    static Path tmp;
    private static Path file;

    @BeforeAll
    static void write() throws Exception {
        file = tmp.resolve("clustered.vortex");
        DType.Struct schema = DType.structBuilder()
                .field("id", DType.I64)
                .field("val", DType.I64)
                .build();
        // enableZoneMaps=true emits the per-chunk min/max/sum/null-count the fold reads.
        WriteOptions opts = new WriteOptions(CHUNK, true, 0.90, 0, true, false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, opts)) {
            for (int c = 0; c < CHUNKS; c++) {
                long[] id = new long[CHUNK];
                long[] val = new long[CHUNK];
                for (int i = 0; i < CHUNK; i++) {
                    long v = (long) c * CHUNK + i;
                    id[i] = v;
                    val[i] = v;
                }
                writer.writeChunk(Map.of("id", id, "val", val));
            }
        }
    }

    @Test
    void cleanPartitionFoldsSumCountMinMaxFromStats() throws Exception {
        // Given a boundary on a chunk edge: id < 3*CHUNK keeps exactly the first three chunks
        int keptChunks = 3;
        long boundary = (long) keptChunks * CHUNK;
        long keptRows = boundary;                 // ids 0 .. boundary-1
        long expectedSum = (boundary - 1) * boundary / 2; // sum 0 .. boundary-1
        String where = "where id < " + boundary;

        try (Connection conn = connect()) {
            // When the four aggregates are taken over the filtered rows, each EXPLAIN shows no scan —
            // answered from the kept zones' statistics
            for (String agg : new String[]{
                    "sum(val)", "count(*)", "count(val)", "min(val)", "max(val)"}) {
                String plan = explain(conn, "select " + agg + " from vtx.t " + where);
                assertThat(plan)
                        .as("clean-partition push-down for %s", agg)
                        .containsIgnoringCase("Values")
                        .doesNotContain("TableScan");
            }

            // And every value equals the full-scan ground truth over the first three chunks
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select sum(val) s, count(*) c, count(val) cv, min(val) mn, max(val) mx "
                                 + "from vtx.t " + where)) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(expectedSum);
                assertThat(rs.getLong("c")).isEqualTo(keptRows);
                assertThat(rs.getLong("cv")).isEqualTo(keptRows);
                assertThat(rs.getLong("mn")).isZero();
                assertThat(rs.getLong("mx")).isEqualTo(boundary - 1);
            }
        }
    }

    @Test
    void emptySelectionFoldsToSqlNullAndZeroCount() throws Exception {
        // Given a boundary that excludes every chunk (id < 0): all zones are OUT, none kept
        try (Connection conn = connect()) {
            // When SUM and COUNT are taken over the empty selection — still no scan
            String plan = explain(conn, "select sum(val), count(*) from vtx.t where id < 0");
            assertThat(plan).containsIgnoringCase("Values").doesNotContain("TableScan");

            // Then SQL SUM over zero rows is NULL and COUNT is 0
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("select sum(val) s, count(*) c from vtx.t where id < 0")) {
                rs.next();
                assertThat(rs.getLong("s")).isZero();
                assertThat(rs.wasNull()).isTrue();   // SUM over the empty set is SQL NULL
                assertThat(rs.getLong("c")).isZero();
            }
        }
    }

    @Test
    void boundaryCuttingAChunkAbandonsToTheScan() throws Exception {
        // Given a boundary one row into chunk 3 (id < 3*CHUNK + 1): chunk 3 is a boundary zone the
        // filter only partially selects, so the fold must be abandoned
        long boundary = (long) 3 * CHUNK + 1;
        String sql = "select sum(val) s, count(*) c from vtx.t where id < " + boundary;

        try (Connection conn = connect()) {
            // When EXPLAIN runs, a scan is present — the rule did not answer from stats
            assertThat(explain(conn, sql)).contains("TableScan");

            // And the scan still produces the exact filtered result (ids 0 .. boundary-1)
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo((boundary - 1) * boundary / 2);
                assertThat(rs.getLong("c")).isEqualTo(boundary);
            }
        }
    }

    private static Connection connect() throws Exception {
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");
        Connection conn = DriverManager.getConnection("jdbc:calcite:", info);
        conn.unwrap(CalciteConnection.class).getRootSchema().add("vtx", new VortexSchema(Map.of("t", file)));
        return conn;
    }

    private static String explain(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("explain plan for " + sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
