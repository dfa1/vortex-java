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

/// Proves ADR 0013 §6 tier-2 step B: a WHERE-filtered aggregate whose predicate *cuts through* a
/// chunk no longer abandons to a full scan. The boundary zones are decoded and reduced under a
/// row-level mask, the whole-zone (IN) zones are folded from statistics, and the two tiers combine
/// into one `Values` row — so the push-down answers the filtered aggregate while decoding only the
/// boundary chunks.
///
/// The fixture is a clustered key `id` (monotone `0 .. ROWS-1`, one contiguous run per chunk) with
/// `val == id` and `CHUNK = 1000`. So chunk `c` covers ids `[1000c, 1000c+999]`. A range whose
/// endpoints fall *inside* chunks (not on chunk edges) produces boundary zones: e.g.
/// `id BETWEEN 2500 AND 5499` leaves chunks 0,1 OUT, chunk 2 a BOUNDARY (only 2500..2999 match),
/// chunks 3,4 IN, chunk 5 a BOUNDARY (only 5000..5499 match), chunks 6,7 OUT. The ground truth is
/// computed by reducing the same in-memory data under the same predicate, so the assertion is
/// "boundary fold == scan result", not a re-derived formula.
class AggregateWhereBoundaryTest {

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
        // enableZoneMaps=true emits the per-chunk min/max/sum/null-count the tier-1 fold reads and the
        // classify() step uses to find the boundary zones.
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
    void betweenStraddlingChunksFoldsSumCountMinMaxViaDecode() throws Exception {
        // Given a BETWEEN whose endpoints land mid-chunk — Calcite lowers it to `id >= lo AND id <=
        // hi`, so chunks 2 and 5 are boundary zones the range cuts through (chunks 3,4 IN, rest OUT).
        long lo = 2_500;
        long hi = 5_499;
        String where = "where id between " + lo + " and " + hi;

        // Ground truth: reduce the identical data under the identical predicate.
        Ground truth = reduce(i -> i >= lo && i <= hi);

        try (Connection conn = connect()) {
            // When the four reductions are taken over the filtered rows, each EXPLAIN folds to a
            // Values with no full scan — the boundary chunks were decoded but the plan is data-free
            for (String agg : new String[]{"sum(val)", "count(*)", "count(val)", "min(val)", "max(val)"}) {
                assertThat(explain(conn, "select " + agg + " from vtx.t " + where))
                        .as("boundary fold for %s", agg)
                        .containsIgnoringCase("Values")
                        .doesNotContain("TableScan");
            }

            // And every folded value equals the ground-truth reduction over the same rows. MIN and
            // MAX (2500 and 5499) come from the two boundary chunks, so this exercises the masked
            // reduce on the boundary path for all four kernels.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select sum(val) s, count(*) c, count(val) cv, min(val) mn, max(val) mx "
                                 + "from vtx.t " + where)) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(truth.sum());
                assertThat(rs.getLong("c")).isEqualTo(truth.count());
                assertThat(rs.getLong("cv")).isEqualTo(truth.count());
                assertThat(rs.getLong("mn")).isEqualTo(truth.min());
                assertThat(rs.getLong("mx")).isEqualTo(truth.max());
            }
        }
    }

    @Test
    void halfOpenRangeWithOneBoundaryFoldsViaDecode() throws Exception {
        // Given a single-sided range cutting one chunk: id < 4321 keeps chunks 0-3 whole (IN) and
        // cuts chunk 4 ([4000,4999]) after id 4320 — exactly one boundary zone, chunks 5-7 OUT.
        long boundary = 4_321;
        String where = "where id < " + boundary;
        Ground truth = reduce(i -> i < boundary);

        try (Connection conn = connect()) {
            // When SUM and COUNT are taken — folded to a Values, no full scan
            assertThat(explain(conn, "select sum(val), count(*) from vtx.t " + where))
                    .containsIgnoringCase("Values").doesNotContain("TableScan");

            // Then the values equal the ground-truth reduction (the IN zones folded from stats plus
            // the one boundary chunk's masked rows)
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select sum(val) s, count(*) c from vtx.t " + where)) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(truth.sum());
                assertThat(rs.getLong("c")).isEqualTo(truth.count());
            }
        }
    }

    @Test
    void nullableAggColumnAcrossBoundaryChunk() throws Exception {
        // Given a non-null clustered id and a nullable val, with nulls in the boundary chunk:
        // chunk 0 is id 0..3 / val {10,null,30,null}, chunk 1 is id 10..13 / val {1,2,3,4}. The
        // predicate `id > 0 AND id < 100` cuts chunk 0 (drops id 0, keeps ids 1,2,3) — a boundary
        // zone — and selects chunk 1 whole (IN).
        DType.Struct schema = new DType.Struct(
                List.of("id", "val"),
                List.of(new DType.Primitive(PType.I64, false), new DType.Primitive(PType.I64, true)),
                false);
        Path f = tmp.resolve("nullable-boundary.vortex");
        writeChunks(f, schema,
                Map.of("id", new long[]{0, 1, 2, 3},
                        "val", new NullableData(new long[]{10, 0, 30, 0}, new boolean[]{true, false, true, false})),
                Map.of("id", new long[]{10, 11, 12, 13},
                        "val", new NullableData(new long[]{1, 2, 3, 4}, new boolean[]{true, true, true, true})));

        try (Connection conn = connect(f)) {
            // When the aggregates are taken — the boundary chunk is decoded and folded, no full scan
            String where = "where id > 0 and id < 100";
            assertThat(explain(conn, "select sum(val), count(*), count(val) from vtx.t " + where))
                    .containsIgnoringCase("Values").doesNotContain("TableScan");

            // Then the kernels skip the boundary chunk's nulls: selected vals are {null,30,null}
            // (ids 1,2,3) plus {1,2,3,4} (chunk 1). SUM = 30 + 10 = 40, COUNT(*) counts all 7
            // selected rows, COUNT(val) the 5 non-null ones (one in chunk 0, four in chunk 1).
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select sum(val) s, count(*) c, count(val) cv from vtx.t " + where)) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(40);
                assertThat(rs.getLong("c")).isEqualTo(7);
                assertThat(rs.getLong("cv")).isEqualTo(5);
            }
        }
    }

    @Test
    void nonNumericMinAcrossBoundaryAbandonsButScanIsCorrect() throws Exception {
        // Given a Utf8 column whose MIN the rule's literal builder cannot represent (it only emits
        // numeric literals): even though the boundary fold can compute the string minimum, the
        // rewrite must abandon so the scan returns the value. chunk 0 id 0..3 / name {"a".."d"},
        // chunk 1 id 10..13 / name {"e".."h"}; `id > 0 AND id < 100` cuts chunk 0 (keeps b,c,d).
        DType.Struct schema = new DType.Struct(
                List.of("id", "name"),
                List.of(new DType.Primitive(PType.I64, false), new DType.Utf8(false)),
                false);
        Path f = tmp.resolve("strmin-boundary.vortex");
        writeChunks(f, schema,
                Map.of("id", new long[]{0, 1, 2, 3}, "name", new String[]{"a", "b", "c", "d"}),
                Map.of("id", new long[]{10, 11, 12, 13}, "name", new String[]{"e", "f", "g", "h"}));

        try (Connection conn = connect(f)) {
            // When MIN over the string column is taken across the boundary — the rewrite abandons, so
            // a scan remains in the plan
            String sql = "select min(name) mn from vtx.t where id > 0 and id < 100";
            assertThat(explain(conn, sql)).contains("TableScan");

            // And the scan still produces the correct minimum over the selected names {b,c,d,e,f,g,h}
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                assertThat(rs.getString("mn")).isEqualTo("b");
            }
        }
    }

    @Test
    void unsignedKeyAcrossBoundaryAbandonsButScanIsCorrect() throws Exception {
        // Given an unsigned (U64) clustered key: a signed stat compare could fold the wrong zones
        // past the high bit, so the fold abandons any unsigned column outright — even on the boundary
        // path. chunk 0 id 0..3, chunk 1 id 10..13; `id > 1 AND id < 13` cuts both chunks.
        DType.Struct schema = new DType.Struct(
                List.of("id", "val"),
                List.of(new DType.Primitive(PType.U64, false), new DType.Primitive(PType.I64, false)),
                false);
        Path f = tmp.resolve("u64-boundary.vortex");
        writeChunks(f, schema,
                Map.of("id", new long[]{0, 1, 2, 3}, "val", new long[]{0, 1, 2, 3}),
                Map.of("id", new long[]{10, 11, 12, 13}, "val", new long[]{10, 11, 12, 13}));

        try (Connection conn = connect(f)) {
            // When the aggregate spans the unsigned key across a boundary — a scan remains in the plan
            String sql = "select sum(val) s, count(*) c from vtx.t where id > 1 and id < 13";
            assertThat(explain(conn, sql)).contains("TableScan");

            // And the unsigned-aware scan produces the correct result: ids 2,3 (vals 2,3) and ids
            // 10,11,12 (vals 10,11,12) → SUM = 5 + 33 = 38, COUNT = 5
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(38);
                assertThat(rs.getLong("c")).isEqualTo(5);
            }
        }
    }

    @Test
    void crossColumnAndStraddlingChunksFoldsViaDecode() throws Exception {
        // Given a conjunction over two DIFFERENT columns whose cuts fall in different chunks: with
        // id == val == i, `id > 2500 AND val < 5499` makes chunk 2 a boundary cut by `id` (only its
        // val<5499 leaf is all-true there) and chunk 5 a boundary cut by `val` (only its id>2500 leaf
        // is all-true there). Each boundary chunk therefore decodes BOTH filter columns and must
        // intersect a partial mask from one column with an all-true mask from the other — exercising
        // intersect() aligning masks across different decoded columns of one chunk (the same-column
        // multi-leaf BETWEEN case already covers a single column's two leaves).
        long lo = 2_500;
        long hi = 5_499;
        String where = "where id > " + lo + " and val < " + hi;

        // Ground truth: reduce the identical data under the identical predicate.
        Ground truth = reduce(i -> i > lo && i < hi);

        try (Connection conn = connect()) {
            // When the reductions are taken over the filtered rows — folded to a Values, no full scan
            assertThat(explain(conn, "select sum(val), count(*), min(val), max(val) from vtx.t " + where))
                    .containsIgnoringCase("Values").doesNotContain("TableScan");

            // Then every folded value equals the ground-truth reduction over the same rows
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select sum(val) s, count(*) c, min(val) mn, max(val) mx from vtx.t " + where)) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(truth.sum());
                assertThat(rs.getLong("c")).isEqualTo(truth.count());
                assertThat(rs.getLong("mn")).isEqualTo(truth.min());
                assertThat(rs.getLong("mx")).isEqualTo(truth.max());
            }
        }
    }

    @Test
    void neqWithNullInFilterColumnAcrossBoundaryExcludesNullAndValue() throws Exception {
        // Given `amt <> 30` over a chunk whose amt has a null: chunk 0 amt {10,null,30,40} is a
        // boundary (30 lies inside [10,40], so classify cannot prove IN/OUT), chunk 1 amt {50,60,70,
        // 80} is IN (30 is outside [50,80] and the chunk has no nulls). Neq lowers to `Lt(30) OR
        // Gt(30)`: both components exclude nulls (SQL UNKNOWN) and neither matches 30 itself, so the
        // null row AND the 30 row must both drop. This confirms the boundary mask's three-valued
        // logic for `<>` — a case the equality/range tests do not reach.
        DType.Struct schema = new DType.Struct(
                List.of("id", "amt"),
                List.of(new DType.Primitive(PType.I64, false), new DType.Primitive(PType.I64, true)),
                false);
        Path f = tmp.resolve("neq-null-boundary.vortex");
        writeChunks(f, schema,
                Map.of("id", new long[]{0, 1, 2, 3},
                        "amt", new NullableData(new long[]{10, 0, 30, 40}, new boolean[]{true, false, true, true})),
                Map.of("id", new long[]{10, 11, 12, 13},
                        "amt", new NullableData(new long[]{50, 60, 70, 80}, new boolean[]{true, true, true, true})));

        // Ground truth: reduce the same data, dropping the null row (UNKNOWN) and the amt == 30 row.
        long[] allAmt = {10, 0, 30, 40, 50, 60, 70, 80};
        boolean[] valid = {true, false, true, true, true, true, true, true};
        long oracleSum = 0;
        long oracleCount = 0;
        long oracleNonNull = 0;
        for (int i = 0; i < allAmt.length; i++) {
            if (valid[i] && allAmt[i] != 30) {
                oracleSum += allAmt[i];
                oracleCount++;
                oracleNonNull++;
            }
        }

        try (Connection conn = connect(f)) {
            // When the aggregates over `amt <> 30` are taken — the boundary chunk is decoded, no scan
            String where = "where amt <> 30";
            assertThat(explain(conn, "select sum(amt), count(*), count(amt) from vtx.t " + where))
                    .containsIgnoringCase("Values").doesNotContain("TableScan");

            // Then the folded values match the oracle: the null row and the amt == 30 row are excluded
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select sum(amt) s, count(*) c, count(amt) cv from vtx.t " + where)) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(oracleSum);
                assertThat(rs.getLong("c")).isEqualTo(oracleCount);
                assertThat(rs.getLong("cv")).isEqualTo(oracleNonNull);
            }
        }
    }

    @Test
    void floatFilterColumnAcrossBoundaryAbandonsButScanIsNaNCorrect() throws Exception {
        // Given a float FILTER column containing NaN in a boundary chunk: the compute-kernel compare
        // sorts NaN as the maximum, so `f >= 1.5` (lowered to Eq OR Gt) would wrongly SELECT the NaN
        // row in a boundary fold that REPLACES the scan — a silent wrong answer. The push-down must
        // abandon any floating-point filter column, leaving a scan whose row-by-row recheck is
        // NaN-correct. chunk 0 f {1.0,NaN,2.0,3.0}, chunk 1 f {4.0,5.0,6.0,7.0}; `f >= 1.5` cuts
        // chunk 0 (a boundary) and keeps chunk 1 whole.
        DType.Struct schema = new DType.Struct(
                List.of("f", "val"),
                List.of(new DType.Primitive(PType.F64, false), new DType.Primitive(PType.I64, false)),
                false);
        Path f = tmp.resolve("float-nan-boundary.vortex");
        writeChunks(f, schema,
                Map.of("f", new double[]{1.0, Double.NaN, 2.0, 3.0}, "val", new long[]{10, 20, 30, 40}),
                Map.of("f", new double[]{4.0, 5.0, 6.0, 7.0}, "val", new long[]{50, 60, 70, 80}));

        // Ground truth: reduce the same data with SQL-NaN-correct `f >= 1.5` (NaN row excluded).
        double[] allF = {1.0, Double.NaN, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0};
        long[] allVal = {10, 20, 30, 40, 50, 60, 70, 80};
        long oracleSum = 0;
        long oracleCount = 0;
        for (int i = 0; i < allF.length; i++) {
            if (allF[i] >= 1.5) {
                oracleSum += allVal[i];
                oracleCount++;
            }
        }

        try (Connection conn = connect(f)) {
            // When SUM/COUNT are taken with a float filter across a boundary — the rewrite abandons,
            // so a scan remains in the plan (the conservative NaN guard)
            String sql = "select sum(val) s, count(*) c from vtx.t where f >= 1.5";
            assertThat(explain(conn, sql)).contains("TableScan");

            // And the NaN-correct scan produces the oracle result (the NaN row is excluded)
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                assertThat(rs.getLong("s")).isEqualTo(oracleSum);
                assertThat(rs.getLong("c")).isEqualTo(oracleCount);
            }
        }
    }

    /// The ground-truth reduction of `val == id` over the rows a predicate selects, computed by
    /// scanning the same `0 .. ROWS-1` data in memory.
    ///
    /// @param sum   the sum of the selected values
    /// @param count the count of selected rows
    /// @param min   the minimum selected value
    /// @param max   the maximum selected value
    private record Ground(long sum, long count, long min, long max) {
    }

    /// Reduces the in-memory `id == val == 0 .. ROWS-1` data under `predicate`, the ground truth the
    /// pushed-down fold must match.
    ///
    /// @param predicate the row test, applied to each value `0 .. ROWS-1`
    /// @return the sum, count, min and max of the selected values
    private static Ground reduce(java.util.function.LongPredicate predicate) {
        long sum = 0;
        long count = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long i = 0; i < ROWS; i++) {
            if (predicate.test(i)) {
                sum += i;
                count++;
                min = Math.min(min, i);
                max = Math.max(max, i);
            }
        }
        return new Ground(sum, count, min, max);
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

    private static Connection connect() throws Exception {
        return connect(file);
    }

    private static Connection connect(Path f) throws Exception {
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");
        Connection conn = DriverManager.getConnection("jdbc:calcite:", info);
        conn.unwrap(CalciteConnection.class).getRootSchema().add("vtx", new VortexSchema(Map.of("t", f)));
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
