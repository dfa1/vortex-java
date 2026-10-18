package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// An `ORDER BY` with no `LIMIT` over a big table is what an IDE grid sends when a column header is
/// clicked. Calcite's own sort buffers every row of the table before it returns the first, which
/// ran a 100M-row table out of memory; the rewrite must instead hand rows back lazily, in the order
/// Calcite's stable sort would, doing work in proportion to how many rows the client reads.
class LazySortScanTest {

    private static final int ROWS = VortexSortPushDownRule.LAZY_SORT_MIN_ROWS + 20_000;

    @TempDir
    static Path tmp;
    private static Path big;
    private static Path small;

    /// `k` repeats (ties must come out in scan order), `n` has nulls, `s` is a string with ties.
    @BeforeAll
    static void writeFiles() throws Exception {
        big = write("big.vortex", ROWS);
        small = write("small.vortex", 500);
    }

    @Test
    void orderByWithoutLimit_overABigTableIsNotSortedByCalcite() throws Exception {
        // Given
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", big))) {

            // When
            String plan = plan(conn, "select * from vtx.t order by k");

            // Then no EnumerableSort buffering the whole table
            assertThat(plan).doesNotContain("Sort");
        }
    }

    @Test
    void orderByWithoutLimit_overASmallTableStaysWithCalcite() throws Exception {
        // Given a table that fits in memory anyway
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", small))) {

            // When
            String plan = plan(conn, "select * from vtx.t order by k");

            // Then
            assertThat(plan).contains("Sort");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"k", "k desc", "s desc, k", "n", "n desc", "n nulls first", "n desc nulls last"})
    void readingEveryRow_matchesAStableSort(String orderBy) throws Exception {
        // Given
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", big))) {

            // When every row is read, past several batch doublings
            String sql = "select id from vtx.t order by " + orderBy;
            List<Long> result = ids(conn, sql);

            // Then the same order a stable sort over the scan gives: ties in scan order, and it was
            // the lazy scan that produced it, not Calcite's sort
            assertThat(plan(conn, sql)).doesNotContain("Sort");
            assertThat(result).isEqualTo(expected(orderBy));
        }
    }

    @Test
    void readingOnlyTheFirstPage_returnsTheTopRows() throws Exception {
        // Given a client that stops after a grid page, as an IDE does
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", big));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select id from vtx.t order by k desc")) {

            // When
            List<Long> result = new ArrayList<>();
            while (result.size() < 500 && rs.next()) {
                result.add(rs.getLong(1));
            }

            // Then
            assertThat(result).isEqualTo(expected("k desc").subList(0, 500));
        }
    }

    @Test
    void filterAndProjection_stillSortLazilyAndCorrectly() throws Exception {
        // Given
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", big))) {

            // When
            String sql = "select id from vtx.t where k > 900 order by s desc, k";
            List<Long> result = ids(conn, sql);

            // Then
            List<Long> expected = IntStream.range(0, ROWS).boxed()
                    .filter(i -> key(i) > 900)
                    .sorted(Comparator.<Integer, String>comparing(LazySortScanTest::text).reversed()
                            .thenComparingLong(LazySortScanTest::key))
                    .map(Integer::longValue).toList();
            assertThat(plan(conn, sql)).doesNotContain("Sort");
            assertThat(result).isEqualTo(expected);
        }
    }

    private static List<Long> expected(String orderBy) {
        Comparator<Integer> byK = Comparator.comparingLong(LazySortScanTest::key);
        Comparator<Integer> order = switch (orderBy) {
            case "k" -> byK;
            case "k desc" -> byK.reversed();
            case "s desc, k" -> Comparator.<Integer, String>comparing(LazySortScanTest::text).reversed().thenComparing(byK);
            // Calcite's default null collation is HIGH: a null is the largest value, so it ends an
            // ascending sort and starts a descending one
            case "n" -> Comparator.comparing(LazySortScanTest::nullable, Comparator.nullsLast(Comparator.<Long>naturalOrder()));
            case "n desc" -> Comparator.comparing(LazySortScanTest::nullable, Comparator.nullsFirst(Comparator.<Long>reverseOrder()));
            case "n nulls first" -> Comparator.comparing(LazySortScanTest::nullable, Comparator.nullsFirst(Comparator.<Long>naturalOrder()));
            case "n desc nulls last" -> Comparator.comparing(LazySortScanTest::nullable, Comparator.nullsLast(Comparator.<Long>reverseOrder()));
            default -> throw new IllegalArgumentException(orderBy);
        };
        return IntStream.range(0, ROWS).boxed().sorted(order).map(Integer::longValue).toList();
    }

    private static long key(int i) {
        return (i * 7919L) % 1000;
    }

    private static Long nullable(int i) {
        return i % 7 == 0 ? null : (i * 31L) % 500;
    }

    private static String text(int i) {
        return "s" + (i % 13);
    }

    private static Path write(String name, int rows) throws Exception {
        Path file = tmp.resolve(name);
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("k"), DType.I64)
                .field(ColumnName.of("n"), DType.I64.asNullable())
                .field(ColumnName.of("s"), new DType.Utf8(false))
                .build();
        long[] id = new long[rows];
        long[] k = new long[rows];
        Long[] n = new Long[rows];
        String[] s = new String[rows];
        for (int i = 0; i < rows; i++) {
            id[i] = i;
            k[i] = key(i);
            n[i] = nullable(i);
            s[i] = text(i);
        }
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("id"), id, ColumnName.of("k"), k,
                    ColumnName.of("n"), n, ColumnName.of("s"), s));
        }
        return file;
    }

    private static List<Long> ids(Connection conn, String sql) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    private static String plan(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("explain plan for " + sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
