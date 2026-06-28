package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;

import org.apache.calcite.jdbc.CalciteConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/// Proves the WHERE-filtered aggregate push-down (ADR 0018): when a pushed predicate partitions the
/// zones cleanly — every chunk fully selected or fully excluded, no boundary — `SUM`/`COUNT`/`MIN`/
/// `MAX` are folded from the kept zones' statistics with no data segment decoded, and the answer
/// matches the full-scan ground truth. When the predicate cuts through a chunk, the boundary tier
/// (ADR 0013 §6 step B) decodes only that chunk and reduces its selected rows, still folding to a
/// `Values` without a full scan; the broader boundary coverage lives in [AggregateWhereBoundaryTest].
/// A predicate the translation cannot capture in full still abandons to the scan.
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
    void boundaryCuttingAChunkFoldsViaDecode() throws Exception {
        // Given a boundary one row into chunk 3 (id < 3*CHUNK + 1): chunks 0-2 are IN (folded from
        // stats), chunks 4-7 are OUT (skipped), and chunk 3 is a boundary the filter cuts after its
        // first row. The boundary tier (ADR 0013 §6 step B) decodes only chunk 3 and reduces the one
        // selected row, so the rewrite folds to a Values without a full scan.
        long boundary = (long) 3 * CHUNK + 1;
        String sql = "select sum(val) s, count(*) c from vtx.t where id < " + boundary;

        try (Connection conn = connect()) {
            // When EXPLAIN runs, no scan is present — the boundary chunk was decoded and folded
            assertThat(explain(conn, sql)).containsIgnoringCase("Values").doesNotContain("TableScan");

            // And the folded result equals the full-scan ground truth over ids 0 .. boundary-1
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo((boundary - 1) * boundary / 2);
                assertThat(rs.getLong("c")).isEqualTo(boundary);
            }
        }
    }

    @Test
    void narrowIntegerKeyFoldsFromStats() throws Exception {
        // Given a clustered I32 key (and I32 value): the zone-map stats box as Integer, not Long, so
        // a same-type comparison would never fire — this guards that the widening compare folds
        // narrow columns. Two chunks: ids 0..3, then 10..13.
        DType.Struct schema = new DType.Struct(
                List.of("id", "val"),
                List.of(new DType.Primitive(PType.I32, false), new DType.Primitive(PType.I32, false)),
                false);
        Path f = tmp.resolve("i32.vortex");
        writeChunks(f, schema,
                Map.of("id", new int[]{0, 1, 2, 3}, "val", new int[]{0, 1, 2, 3}),
                Map.of("id", new int[]{10, 11, 12, 13}, "val", new int[]{10, 11, 12, 13}));

        try (Connection conn = connect(f)) {
            // When the first chunk is selected (id < 10), the aggregates fold from stats — no scan
            String plan = explain(conn, "select sum(val), count(*), min(val), max(val) from vtx.t where id < 10");
            assertThat(plan).containsIgnoringCase("Values").doesNotContain("TableScan");

            // Then the values match the first chunk (ids 0..3)
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select sum(val) s, count(*) c, min(val) mn, max(val) mx from vtx.t where id < 10")) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(6);     // 0+1+2+3
                assertThat(rs.getLong("c")).isEqualTo(4);
                assertThat(rs.getLong("mn")).isZero();
                assertThat(rs.getLong("mx")).isEqualTo(3);
            }
        }
    }

    @Test
    void nullAwareSumAndCountOverKeptZones() throws Exception {
        // Given a non-null clustered key and a nullable value with two nulls in the kept chunk
        DType.Struct schema = new DType.Struct(
                List.of("id", "val"),
                List.of(new DType.Primitive(PType.I64, false), new DType.Primitive(PType.I64, true)),
                false);
        Path f = tmp.resolve("nullable.vortex");
        writeChunks(f, schema,
                Map.of("id", new long[]{0, 1, 2, 3},
                        "val", new NullableData(new long[]{10, 0, 30, 0}, new boolean[]{true, false, true, false})),
                Map.of("id", new long[]{10, 11, 12, 13},
                        "val", new NullableData(new long[]{1, 2, 3, 4}, new boolean[]{true, true, true, true})));

        try (Connection conn = connect(f)) {
            // When the first chunk is selected (id < 10) — folded from stats, no scan
            String plan = explain(conn, "select sum(val), count(*), count(val) from vtx.t where id < 10");
            assertThat(plan).containsIgnoringCase("Values").doesNotContain("TableScan");

            // Then SUM ignores the nulls (10+30), COUNT(*) counts every row, COUNT(val) the non-null
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select sum(val) s, count(*) c, count(val) cv from vtx.t where id < 10")) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(40);    // 10 + 30, the two nulls skipped
                assertThat(rs.getLong("c")).isEqualTo(4);
                assertThat(rs.getLong("cv")).isEqualTo(2);
            }
        }
    }

    @Test
    void unsignedKeyColumnAbandonsToScan() throws Exception {
        // Given a clustered U64 key: a signed stat compare could fold the wrong zones past the high
        // bit, so the fold must abandon unsigned columns to the (unsigned-aware) scan
        DType.Struct schema = new DType.Struct(
                List.of("id", "val"),
                List.of(new DType.Primitive(PType.U64, false), new DType.Primitive(PType.I64, false)),
                false);
        Path f = tmp.resolve("u64.vortex");
        writeChunks(f, schema,
                Map.of("id", new long[]{0, 1, 2, 3}, "val", new long[]{0, 1, 2, 3}),
                Map.of("id", new long[]{10, 11, 12, 13}, "val", new long[]{10, 11, 12, 13}));

        try (Connection conn = connect(f)) {
            // When id < 10 over the unsigned key — a scan is present (no stats fold)
            String sql = "select sum(val) s, count(*) c from vtx.t where id < 10";
            assertThat(explain(conn, sql)).contains("TableScan");

            // And the scan still produces the exact result over the first chunk
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(6);
                assertThat(rs.getLong("c")).isEqualTo(4);
            }
        }
    }

    @Test
    void isNullFoldsFromStatsOnCleanPartition() throws Exception {
        // Given a nullable val whose null-ness partitions the zones cleanly: chunk 0 is entirely
        // NULL, chunk 1 entirely non-null. `val IS NULL` then selects chunk 0 whole and excludes
        // chunk 1 whole — a clean partition the zone-map null counts decide without a decode.
        Path f = nullPartitionedFile("isnull-clean.vortex");

        try (Connection conn = connect(f)) {
            // When the aggregates are taken over the rows where val IS NULL — answered from stats
            String plan = explain(conn, "select sum(val), count(*), count(val) from vtx.t where val is null");
            assertThat(plan).containsIgnoringCase("Values").doesNotContain("TableScan");

            // Then SUM over the all-null selection is SQL NULL, COUNT(*) is the kept chunk's 4 rows,
            // and COUNT(val) is 0 (every selected val is null)
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select sum(val) s, count(*) c, count(val) cv from vtx.t where val is null")) {
                rs.next();
                assertThat(rs.getLong("s")).isZero();
                assertThat(rs.wasNull()).isTrue();   // SUM over an all-null selection is SQL NULL
                assertThat(rs.getLong("c")).isEqualTo(4);
                assertThat(rs.getLong("cv")).isZero();
            }
        }
    }

    @Test
    void isNotNullFoldsFromStatsOnCleanPartition() throws Exception {
        // Given the same clean partition (chunk 0 all NULL, chunk 1 all non-null {10,20,30,40}):
        // `val IS NOT NULL` selects chunk 1 whole and excludes chunk 0 whole.
        Path f = nullPartitionedFile("isnotnull-clean.vortex");

        try (Connection conn = connect(f)) {
            // When SUM/COUNT/MIN/MAX are taken over the non-null rows — answered from stats
            String plan = explain(conn,
                    "select sum(val), count(*), count(val), min(val), max(val) from vtx.t where val is not null");
            assertThat(plan).containsIgnoringCase("Values").doesNotContain("TableScan");

            // Then every value equals the ground truth over chunk 1's {10,20,30,40}
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select sum(val) s, count(*) c, count(val) cv, min(val) mn, max(val) mx "
                                 + "from vtx.t where val is not null")) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(100);  // 10+20+30+40
                assertThat(rs.getLong("c")).isEqualTo(4);
                assertThat(rs.getLong("cv")).isEqualTo(4);
                assertThat(rs.getLong("mn")).isEqualTo(10);
                assertThat(rs.getLong("mx")).isEqualTo(40);
            }
        }
    }

    @Test
    void isNotNullBoundaryChunkFoldsViaDecode() throws Exception {
        // Given a chunk the null predicate cuts through: chunk 0 mixes non-null {5,7} with two
        // nulls, so its null count is neither 0 nor the row count — a boundary zone `IS NOT NULL`
        // only partially selects. Chunk 1 is all non-null (IN). The boundary tier decodes chunk 0,
        // builds an IS NOT NULL mask, and reduces only its selected rows, so the fold answers
        // without a full scan.
        DType.Struct schema = new DType.Struct(
                List.of("id", "val"),
                List.of(new DType.Primitive(PType.I64, false), new DType.Primitive(PType.I64, true)),
                false);
        Path f = tmp.resolve("isnotnull-boundary.vortex");
        writeChunks(f, schema,
                Map.of("id", new long[]{0, 1, 2, 3},
                        "val", new NullableData(new long[]{5, 0, 7, 0}, new boolean[]{true, false, true, false})),
                Map.of("id", new long[]{10, 11, 12, 13},
                        "val", new NullableData(new long[]{10, 20, 30, 40}, new boolean[]{true, true, true, true})));

        try (Connection conn = connect(f)) {
            // When EXPLAIN runs, no scan is present — the boundary chunk was decoded and folded
            String sql = "select sum(val) s, count(*) c from vtx.t where val is not null";
            assertThat(explain(conn, sql)).containsIgnoringCase("Values").doesNotContain("TableScan");

            // And the folded result equals the ground truth over every non-null val ({5,7}+{10,20,30,40})
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(112);  // 5+7 + 10+20+30+40
                assertThat(rs.getLong("c")).isEqualTo(6);
            }
        }
    }

    /// One untranslatable `WHERE` shape: its predicate, the SQL `SUM(val)` ground truth (`null` for
    /// an empty selection, where `SUM` is SQL NULL), and the `COUNT(*)` ground truth. All run over
    /// the shared 8-chunk `id == val` fixture (`0 .. ROWS-1`, non-null).
    private record AbandonCase(String label, String where, Long expectedSum, long expectedCount) {
        @Override
        public String toString() {
            return label;
        }
    }

    static java.util.stream.Stream<AbandonCase> untranslatableShapes() {
        // Each shape is a predicate strictComparison cannot fully translate to a RowFilter, so the
        // aggregate rewrite must abandon and let the scan compute the answer. The ground truth uses
        // val == id over 0..ROWS-1.
        return java.util.stream.Stream.of(
                // OR has no case in strictComparison's switch (only AND / comparison / null check),
                // so it hits the default -> empty. ids 0..999 match; val<0 never does.
                new AbandonCase("OR predicate", "id < 1000 or val < 0",
                        999L * 1000 / 2, 1000),
                // column-vs-column: binary() requires a RexInputRef + a RexLiteral; two refs abandon.
                // id <= val is always true, so the scan reduces over the whole table.
                new AbandonCase("column <= column (all rows)", "id <= val",
                        (long) (ROWS - 1) * ROWS / 2, ROWS),
                // column-vs-column with an empty selection (id < val is never true): still abandons,
                // and the scan over zero rows makes SUM the SQL NULL, COUNT(*) zero.
                new AbandonCase("column < column (no rows)", "id < val", null, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("untranslatableShapes")
    void untranslatablePredicateAbandonsToScan(AbandonCase shape) throws Exception {
        String sql = "select sum(val) s, count(*) c from vtx.t where " + shape.where();

        try (Connection conn = connect()) {
            // When the predicate is not fully translatable, the rewrite is abandoned: the plan keeps
            // a scan (BindableTableScan) rather than folding to a Values row
            assertThat(explain(conn, sql))
                    .as("abandon for %s", shape.label())
                    .contains("TableScan");

            // And the scan still produces the exact aggregate over the filtered rows
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                long sum = rs.getLong("s");
                if (shape.expectedSum() == null) {
                    assertThat(rs.wasNull()).as("SUM over empty selection is SQL NULL").isTrue();
                } else {
                    assertThat(sum).as("SUM for %s", shape.label()).isEqualTo(shape.expectedSum());
                }
                assertThat(rs.getLong("c")).as("COUNT(*) for %s", shape.label())
                        .isEqualTo(shape.expectedCount());
            }
        }
    }

    @Test
    void isNullOverExpressionAbandonsToScan() throws Exception {
        // Given a nullable val: `abs(val) IS NULL` is an IS NULL over a *computed* expression, not a
        // bare column ref, so nullCheck's RexInputRef guard rejects it and the rewrite abandons.
        // (abs() is chosen deliberately — Calcite simplifies `(val + 1) IS NULL` down to a bare
        // `val IS NULL`, which would translate and fold, but it leaves `IS NULL(ABS(val))` intact.)
        Path f = nullPartitionedFile("isnull-expr.vortex");
        String sql = "select sum(val) s, count(*) c, count(val) cv from vtx.t where abs(val) is null";

        try (Connection conn = connect(f)) {
            // When EXPLAIN runs, a scan is present — the expression IS NULL did not translate
            assertThat(explain(conn, sql)).contains("TableScan");

            // And the scan computes the exact result: abs(val) IS NULL selects chunk 0's all-null
            // rows, so SUM is SQL NULL, COUNT(*) the 4 rows, COUNT(val) zero
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                assertThat(rs.getLong("s")).isZero();
                assertThat(rs.wasNull()).isTrue();   // SUM over an all-null selection is SQL NULL
                assertThat(rs.getLong("c")).isEqualTo(4);
                assertThat(rs.getLong("cv")).isZero();
            }
        }
    }

    /// Writes a two-chunk file whose nullable `val` partitions the zones cleanly by null-ness:
    /// chunk 0 entirely NULL, chunk 1 entirely non-null `{10,20,30,40}`. Shared by the
    /// `IS NULL` / `IS NOT NULL` clean-partition tests.
    private static Path nullPartitionedFile(String name) throws Exception {
        DType.Struct schema = new DType.Struct(
                List.of("id", "val"),
                List.of(new DType.Primitive(PType.I64, false), new DType.Primitive(PType.I64, true)),
                false);
        Path f = tmp.resolve(name);
        writeChunks(f, schema,
                Map.of("id", new long[]{0, 1, 2, 3},
                        "val", new NullableData(new long[]{0, 0, 0, 0}, new boolean[]{false, false, false, false})),
                Map.of("id", new long[]{10, 11, 12, 13},
                        "val", new NullableData(new long[]{10, 20, 30, 40}, new boolean[]{true, true, true, true})));
        return f;
    }

    private static void writeChunks(Path file, DType.Struct schema, Map<String, Object> chunk0,
                                    Map<String, Object> chunk1) throws Exception {
        // chunkSize large so each writeChunk is exactly one chunk (one zone).
        WriteOptions opts = new WriteOptions(1024, true, 0.90, 0, false, false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, opts)) {
            writer.writeChunk(chunk0);
            writer.writeChunk(chunk1);
        }
    }

    private static Connection connect(Path f) throws Exception {
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");
        Connection conn = DriverManager.getConnection("jdbc:calcite:", info);
        conn.unwrap(CalciteConnection.class).getRootSchema().add("vtx", new VortexSchema(Map.of("t", f)));
        return conn;
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
