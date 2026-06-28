package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.apache.calcite.jdbc.CalciteConnection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

/// Drives [VortexTable]'s filter push-down (`scan(root, filters, projects)`) through a real Calcite
/// JDBC planner: every supported `WHERE` comparison must be translated into a zone-map [RowFilter]
/// (so the scan can prune chunks) while Calcite still returns the exact rows. Predicates the
/// translator does not understand must be left untouched, not break the query.
class FilterPushDownTest {

    // Two chunks of three rows so the pushed RowFilter has chunks to (potentially) prune.
    private static final DType.Struct SCHEMA = DType.structBuilder()
            .field("i64", DType.I64)
            .field("i32", DType.I32)
            .field("f64", DType.F64)
            .field("s", DType.UTF8)
            .field("b", DType.BOOL)
            .build();

    @TempDir
    static Path tmp;
    private static Path file;

    @BeforeAll
    static void write() throws Exception {
        file = tmp.resolve("filter.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            w.writeChunk(Map.of(
                    "i64", new long[]{1000L, 2000L, 3000L},
                    "i32", new int[]{100, 200, 300},
                    "f64", new double[]{1.0, 2.0, 3.0},
                    "s", new String[]{"a", "b", "c"},
                    "b", new boolean[]{true, false, true}));
            w.writeChunk(Map.of(
                    "i64", new long[]{4000L, 5000L, 6000L},
                    "i32", new int[]{400, 500, 600},
                    "f64", new double[]{4.0, 5.0, 6.0},
                    "s", new String[]{"d", "e", "f"},
                    "b", new boolean[]{false, true, false}));
        }
    }

    @ParameterizedTest(name = "[{index}] WHERE {0} -> {1} rows")
    @CsvSource({
            // every comparison kind the RowFilter translator supports, over a Long column
            "i64 = 1000,            1",   // EQUALS        -> Column(i64, Predicate.Eq)
            "i64 <> 1000,           5",   // NOT_EQUALS    -> Column(i64, Predicate.Neq)
            "i64 < 3000,            2",   // LESS_THAN     -> Column(i64, Predicate.Lt)
            "i64 <= 3000,           3",   // LESS_THAN_EQ  -> Column(i64, Predicate.Lte)
            "i64 > 4000,            2",   // GREATER_THAN  -> Column(i64, Predicate.Gt)
            "i64 >= 4000,           3",   // GREATER_EQ    -> Column(i64, Predicate.Gte)
            "s = 'a',               1",   // Utf8 literal coercion
            "f64 > 3.0,             3",   // floating literal coercion
            // multiple conjuncts arrive as separate filters -> RowFilter.and over the list
            "i64 > 1000 AND i32 < 600, 4",
            // bare boolean ref is not a RexCall -> not pushed, query still exact
            "b,                     3",
            // comparison on a BOOLEAN column has no zone-map coercion -> not pushed, still exact
            "b = true,              3"
    })
    void whereClauseIsPushedAndRowsStayExact(String where, int expected) throws Exception {
        // Given a Calcite JDBC connection over the Vortex file
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");
        try (Connection conn = DriverManager.getConnection("jdbc:calcite:", info)) {
            conn.unwrap(CalciteConnection.class).getRootSchema()
                    .add("vtx", new VortexSchema(Map.of("data", file)));

            // When the filtered query runs (Calcite pushes the predicate into VortexTable.scan)
            int rows = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("select i64 from vtx.data where " + where)) {
                while (rs.next()) {
                    rows++;
                }
            }

            // Then the row count is exact regardless of whether the predicate was pushed
            assertThat(rows).isEqualTo(expected);
        }
    }
}
