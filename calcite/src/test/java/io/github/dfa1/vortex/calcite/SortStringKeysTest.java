package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

/// The `ORDER BY ... LIMIT` top-N walks a chunk sorted against the query backward and skips string
/// rows by their bytes. Each query is checked against Calcite's own sort of the same rows (the key
/// wrapped in a `CASE` the push-down rule cannot take), or against the rows worked out by hand.
class SortStringKeysTest {

    // written with the legacy zone map, which keeps each batch a chunk of its own: default writes
    // coalesce small batches into one chunk
    private static final int BATCH_ROWS = 1_000;

    @TempDir
    static Path tmp;
    private static Path events;
    /// The first row of the events file's second chunk.
    private static long edge;
    private static Path tiny;
    private static Path unsigned;

    @BeforeAll
    static void writeFiles() throws Exception {
        // `t` ascends with the rows in runs of seven equal values; 1,000 rows a chunk is not a
        // multiple of 7, so a run straddles every chunk edge
        events = tmp.resolve("events.vortex");
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("t"), DType.UTF8)
                .build();
        try (var ch = FileChannel.open(events, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema, LegacyZoneMaps.OPTIONS)) {
            for (int batch = 0; batch < 3; batch++) {
                long[] id = new long[BATCH_ROWS];
                String[] t = new String[BATCH_ROWS];
                for (int i = 0; i < BATCH_ROWS; i++) {
                    id[i] = (long) batch * BATCH_ROWS + i;
                    t[i] = "K%05d".formatted(id[i] / 7);
                }
                writer.writeChunk(Map.of(ColumnName.of("id"), id, ColumnName.of("t"), t));
            }
        }
        try (VortexReader reader = VortexReader.open(events);
             var scan = reader.scan(ScanOptions.all())) {
            edge = scan.chunkRowCounts()[0];
        }
        // fewer rows than the five a direction sample reads
        tiny = tmp.resolve("tiny.vortex");
        try (var ch = FileChannel.open(tiny, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{0, 1, 2},
                    ColumnName.of("t"), new String[]{"a", "b", "c"}));
        }
        // rows 0..4 hold u64 values past BIGINT, which fail when read; rows 5..9 hold 95..91
        unsigned = tmp.resolve("unsigned.vortex");
        DType.Struct unsignedSchema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("u"), new DType.Primitive(PType.U64, false))
                .build();
        long[] u = new long[10];
        for (int i = 0; i < 10; i++) {
            u[i] = i < 5 ? Long.MIN_VALUE + i : 100 - i;
        }
        try (var ch = FileChannel.open(unsigned, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, unsignedSchema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
                    ColumnName.of("u"), u));
        }
    }

    @Test
    void eventsFile_spansSeveralChunks() throws Exception {
        // Given
        long[] result;

        // When
        try (VortexReader reader = VortexReader.open(events);
             var scan = reader.scan(ScanOptions.all())) {
            result = scan.chunkRowCounts();
        }

        // Then — the queries below cross chunk edges only if the writer kept the chunks apart
        assertThat(result).hasSizeGreaterThan(1);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // sorted against the query: every chunk walked backward
            "order by t desc limit 15                            | order by case when id > -1 then t end desc limit 15",
            // `EDGE` is the first row of the second chunk. The top rows end just past it, so the run of
            // seven tied values around it is split between a chunk walked backward (its sample all
            // kept) and one walked forward (its sample cut by the filter)
            "where id < EDGE + 3 order by t desc limit 10         | where id < EDGE + 3 order by case when id > -1 then t end desc limit 10",
            // sorted with the query: walked forward, from just before the edge, across it
            "where id >= EDGE - 5 order by t limit 9              | where id >= EDGE - 5 order by case when id > -1 then t end limit 9",
            "where id >= EDGE - 20 order by t desc, id limit 12 offset 3 | where id >= EDGE - 20 order by case when id > -1 then t end desc, id limit 12 offset 3"})
    void orderByStringLimit_returnsTheRowsCalciteSortReturns(String tail, String oracleTail) throws Exception {
        // Given
        assertThat(edge).as("the events file holds several chunks").isLessThan(3L * BATCH_ROWS);
        String sql = "select id, t from vtx.t " + tail.replace("EDGE", Long.toString(edge));
        String oracle = "select id, t from vtx.t " + oracleTail.replace("EDGE", Long.toString(edge));

        // When
        List<String> result = rows(events, sql);

        // Then
        assertThat(plan(events, sql)).contains("Values").doesNotContain("TableScan");
        assertThat(result).isEqualTo(rows(events, oracle)).isNotEmpty();
    }

    @Test
    void chunkSmallerThanTheSample_isWalkedForward() throws Exception {
        // Given
        String sql = "select id, t from vtx.t order by t desc limit 2";

        // When
        List<String> result = rows(tiny, sql);

        // Then
        assertThat(result).containsExactly("[2, c]", "[1, b]");
    }

    @Test
    void sampleRowTheFilterRejects_isNeverRead() throws Exception {
        // Given — sampling a filtered-out row read a u64 past BIGINT, which fails, although the
        // filter keeps only rows 5..9
        String sql = "select id, u from vtx.t where id >= 5 order by u limit 3";

        // When
        List<String> result = rows(unsigned, sql);

        // Then
        assertThat(plan(unsigned, sql)).contains("Values");
        assertThat(result).containsExactly("[9, 91]", "[8, 92]", "[7, 93]");
    }

    private static String plan(Path file, String sql) throws Exception {
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("explain plan for " + sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> rows(Path file, String sql) throws Exception {
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
