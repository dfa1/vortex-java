package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;

import org.apache.calcite.jdbc.CalciteConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/// Demo: SQL over a 1M-row OHLC Vortex file via the Calcite adapter.
///
/// [#aggregatesMatchAndPushdownStaysFlatOverRepeatedQueries] compares the Calcite full scan
/// against Vortex zone-map push-down; [#variousQueryShapes] exercises GROUP BY / WHERE / HAVING
/// / ORDER BY+LIMIT / IN to show the adapter handles real SQL. Those shapes currently run as
/// full scans — they are exactly what Phase 1 (filter/project push-down) and Phase 2 (aggregate
/// push-down) in ADR 0018 will accelerate.
class OhlcSqlDemoTest {

    private static final int ROWS = 1_000_000;
    private static final int CHUNK = 10_000;
    private static final int WARMUP = 3;
    private static final int ITERATIONS = 10;

    // MIN/MAX/COUNT only — the aggregates Vortex answers purely from zone-map stats, with no data
    // segment decoded. SUM/AVG are deliberately left out: there is no per-zone SUM stat yet, so they
    // would force a full scan and hide the real push-down cost (sub-millisecond here).
    private static final String AGG_SQL =
            "select min(low) lo, max(high) hi, count(*) c from vtx.ohlc";

    @TempDir
    static Path tmp;
    private static Path file;

    private record SqlAggs(double minLow, double maxHigh, long count) {
    }

    private record Pushdown(Object minLow, Object maxHigh, long count) {
    }

    @BeforeAll
    static void writeFile() throws Exception {
        file = tmp.resolve("ohlc.vortex");
        OhlcGenerator.write(file, ROWS, CHUNK);
    }

    @Test
    void aggregatesMatchAndPushdownStaysFlatOverRepeatedQueries() throws Exception {
        // Given the shared OHLC file behind a Calcite schema
        try (Connection conn = connect()) {
            // Warm up both paths so timings reflect steady-state JIT, not first-call compile.
            for (int i = 0; i < WARMUP; i++) {
                runSql(conn);
                runPushdown(file);
            }

            // When each path runs ITERATIONS times, accumulating wall time
            long sqlNanos = 0;
            long pushdownNanos = 0;
            SqlAggs sql = null;
            Pushdown push = null;
            for (int i = 0; i < ITERATIONS; i++) {
                long a = System.nanoTime();
                sql = runSql(conn);
                sqlNanos += System.nanoTime() - a;

                long b = System.nanoTime();
                push = runPushdown(file);
                pushdownNanos += System.nanoTime() - b;
            }

            printTiming(push, sqlNanos, pushdownNanos);

            // Then push-down min/max/count match the full-scan ground truth exactly
            assertThat(((Number) push.minLow()).doubleValue()).isEqualTo(sql.minLow());
            assertThat(((Number) push.maxHigh()).doubleValue()).isEqualTo(sql.maxHigh());
            assertThat(push.count()).isEqualTo(sql.count());
        }
    }

    @Test
    void variousQueryShapes() throws Exception {
        // Given the shared OHLC file behind a Calcite schema
        try (Connection conn = connect()) {
            // When / Then a spread of SQL shapes all run and return sensible results.

            // GROUP BY + ORDER BY + LIMIT: the five most-traded tickers.
            long topRows = printAndCount(conn, "top 5 tickers by total volume",
                    "select symbol, count(*) days, max(high) hi, sum(volume) vol "
                            + "from vtx.ohlc group by symbol order by vol desc limit 5");
            assertThat(topRows).isEqualTo(5);

            // WHERE with a row-level predicate: how many 'up' days (close above open).
            long upDays;
            try (Statement st = conn.createStatement();
                 // `close` and `open` are SQL reserved words (CLOSE/OPEN cursor) — must be quoted.
                 ResultSet rs = st.executeQuery("select count(*) c from vtx.ohlc where `close` > `open`")) {
                rs.next();
                upDays = rs.getLong("c");
            }
            System.out.printf("%n[up days] close > open: %,d of %,d rows%n", upDays, (long) ROWS);
            assertThat(upDays).isBetween(0L, (long) ROWS);

            // WHERE IN + GROUP BY: average volume for a watchlist.
            long watchRows = printAndCount(conn, "avg volume for AAPL/MSFT/NVDA",
                    "select symbol, avg(cast(volume as double)) av from vtx.ohlc "
                            + "where symbol in ('AAPL', 'MSFT', 'NVDA') group by symbol order by symbol");
            assertThat(watchRows).isEqualTo(3);

            // HAVING: tickers whose lifetime volume clears a threshold.
            long bigRows = printAndCount(conn, "tickers with sum(volume) > 33.35e9",
                    "select symbol, sum(volume) v from vtx.ohlc group by symbol "
                            + "having sum(volume) > 33350000000 order by v desc");
            assertThat(bigRows).isBetween(1L, 29L);

            // ORDER BY + LIMIT on raw rows: the three highest single-day highs.
            long peakRows = printAndCount(conn, "3 highest single-day highs",
                    "select symbol, high, volume from vtx.ohlc order by high desc limit 3");
            assertThat(peakRows).isEqualTo(3);
        }
    }

    @Test
    void filterPushDownPrunesChunksAndShowsInExplain() throws Exception {
        // Given the OHLC file (date is strictly increasing → zone-map prunable) behind a schema
        VortexSchema schema = new VortexSchema(Map.of("ohlc", file));
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");
        try (Connection conn = DriverManager.getConnection("jdbc:calcite:", info)) {
            conn.unwrap(CalciteConnection.class).getRootSchema().add("vtx", schema);

            // A narrow date window: ~101 days out of ~33,333, landing in a single 10k-row chunk.
            int startDay = (int) java.time.LocalDate.of(2020, 1, 2).toEpochDay();
            int lo = startDay + 10_000;
            int hi = startDay + 10_100;
            String windowed = "select count(*) c, max(high) h from vtx.ohlc "
                    + "where `date` between " + lo + " and " + hi;

            // When the unfiltered query runs, every chunk is decoded
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("select count(*) c from vtx.ohlc")) {
                rs.next();
            }
            long chunksFull = schema.table("ohlc").chunksScannedLastQuery();

            // And the date-windowed query runs, pruning chunks via zone-map stats
            long windowCount;
            double windowMaxHigh;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(windowed)) {
                rs.next();
                windowCount = rs.getLong("c");
                windowMaxHigh = rs.getDouble("h");
            }
            long chunksPruned = schema.table("ohlc").chunksScannedLastQuery();

            // And EXPLAIN shows the predicate folded into the scan (push-down landed)
            String plan;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("explain plan for " + windowed)) {
                rs.next();
                plan = rs.getString(1);
            }

            System.out.printf("%n[filter push-down] date in [%d, %d]%n", lo, hi);
            System.out.printf("  rows matched: %,d | max(high) in window: %.2f%n", windowCount, windowMaxHigh);
            System.out.printf("  chunks decoded: %d (windowed) vs %d (full) — %.0f%% skipped by zone maps%n",
                    chunksPruned, chunksFull, 100.0 * (chunksFull - chunksPruned) / chunksFull);
            System.out.println("  EXPLAIN:");
            plan.lines().forEach(l -> System.out.println("    " + l));

            // Then the windowed scan touched far fewer chunks than the full scan, exact result intact
            assertThat(chunksFull).isEqualTo(ROWS / CHUNK);
            assertThat(chunksPruned).isLessThanOrEqualTo(3);
            assertThat(windowCount).isBetween(2900L, 3100L);
            assertThat(windowMaxHigh).isLessThanOrEqualTo(5347.11);
            // And the plan is a Bindable scan carrying pushed filters
            assertThat(plan).contains("BindableTableScan").contains("filters");
        }
    }

    private static Connection connect() throws Exception {
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");
        Connection conn = DriverManager.getConnection("jdbc:calcite:", info);
        conn.unwrap(CalciteConnection.class).getRootSchema()
                .add("vtx", new VortexSchema(Map.of("ohlc", file)));
        return conn;
    }

    private static SqlAggs runSql(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(AGG_SQL)) {
            rs.next();
            return new SqlAggs(rs.getDouble("lo"), rs.getDouble("hi"), rs.getLong("c"));
        }
    }

    /// Push-down path: read MIN(low)/MAX(high) straight from footer zone-map stats and COUNT(*) from
    /// chunk metadata — no data segment is decoded.
    private static Pushdown runPushdown(Path file) throws Exception {
        try (VortexReader reader = VortexReader.open(file)) {
            var stats = reader.columnStats();
            long total = 0;
            try (ScanIterator scan = reader.scan(ScanOptions.all())) {
                for (long c : scan.chunkRowCounts()) {
                    total += c;
                }
            }
            return new Pushdown(stats.get("low").min(), stats.get("high").max(), total);
        }
    }

    /// Runs a query, prints every row as a labelled table, and returns the row count.
    private static long printAndCount(Connection conn, String title, String sql) throws Exception {
        System.out.printf("%n[%s]%n", title);
        long rows = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            StringBuilder header = new StringBuilder("  ");
            for (int c = 1; c <= cols; c++) {
                header.append(String.format("%-16s", md.getColumnLabel(c)));
            }
            System.out.println(header);
            while (rs.next()) {
                StringBuilder line = new StringBuilder("  ");
                for (int c = 1; c <= cols; c++) {
                    line.append(String.format("%-16s", rs.getObject(c)));
                }
                System.out.println(line);
                rows++;
            }
        }
        return rows;
    }

    private static void printTiming(Pushdown push, long sqlNanos, long pushdownNanos) {
        double sqlMs = sqlNanos / 1e6 / ITERATIONS;
        double pushMs = pushdownNanos / 1e6 / ITERATIONS;
        System.out.println();
        System.out.printf("OHLC MIN/MAX/COUNT (%,d rows, %d zones) — %d repeated queries%n",
                ROWS, ROWS / CHUNK, ITERATIONS);
        System.out.printf("  %-14s %-22s %s%n", "AGGREGATE", "VALUE", "SOURCE");
        System.out.printf("  %-14s %-22s %s%n", "MIN(low)", push.minLow(), "ZONE_STATS_PUSHDOWN");
        System.out.printf("  %-14s %-22s %s%n", "MAX(high)", push.maxHigh(), "ZONE_STATS_PUSHDOWN");
        System.out.printf("  %-14s %-22d %s%n", "COUNT(*)", push.count(), "ZONE_STATS_PUSHDOWN");
        System.out.printf("  per-query avg — full scan (Calcite): %.2f ms | push-down (stats only): %.3f ms | %.0fx%n",
                sqlMs, pushMs, sqlMs / pushMs);
    }
}
