package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.calcite.VortexSchema;
import io.github.dfa1.vortex.cli.tui.term.Key;
import io.github.dfa1.vortex.cli.tui.term.Terminal;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.apache.calcite.jdbc.CalciteConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/// Drives the SQL shell loop with a scripted [FakeTerminal] over a real in-memory Calcite
/// connection backed by a tiny Vortex fixture. Assertions are made on the captured terminal
/// output (data values, column labels) and on the recall buffer, since the loop has no other
/// observable surface.
class VortexSqlTuiTest {

    private static final Terminal.Size SIZE = new Terminal.Size(24, 80);

    @Test
    void run_queryEndingInSemicolon_executesAndRendersResults(@TempDir Path dir) throws Exception {
        // Given — a fixture table `t` and a query typed in full, terminated by ';'.
        Path file = writeFixture(dir);
        QueryHistory history = QueryHistory.load(dir.resolve("hist"));
        FakeTerminal term = new FakeTerminal(SIZE, type("select name from vtx.t;\n"));

        // When
        runWith(term, file, history);

        // Then — the result grid shows the column header and every row value.
        assertThat(term.output()).contains("name").contains("alice", "bob", "carol");
        // And the query is recorded in history.
        assertThat(history.entries()).containsExactly("select name from vtx.t;");
    }

    @Test
    void run_backspaceCorrectsTypoBeforeRunning(@TempDir Path dir) throws Exception {
        // Given — a typo "namx", one Backspace, then the correct suffix and terminator.
        Path file = writeFixture(dir);
        List<Key> script = new ArrayList<>(type("select namx"));
        script.add(Key.Backspace.INSTANCE);
        script.addAll(type("e from vtx.t;\n"));
        FakeTerminal term = new FakeTerminal(SIZE, script);

        // When
        runWith(term, file, QueryHistory.load(dir.resolve("hist")));

        // Then — the corrected query runs and produces the expected rows.
        assertThat(term.output()).contains("alice");
    }

    @Test
    void run_enterInsertsNewlineUntilSemicolonTerminates(@TempDir Path dir) throws Exception {
        // Given — Enter on a line not ending in ';' must add a newline, not run.
        Path file = writeFixture(dir);
        List<Key> script = new ArrayList<>(type("select name"));
        script.add(Key.Enter.INSTANCE);            // newline, no run
        script.addAll(type("from vtx.t;\n"));      // now ends with ';' -> run
        FakeTerminal term = new FakeTerminal(SIZE, script);

        // When
        runWith(term, file, QueryHistory.load(dir.resolve("hist")));

        // Then — the continuation gutter is rendered and the multiline query runs.
        assertThat(term.output()).contains("  |  ").contains("alice");
    }

    @Test
    void run_arrowUpRecallsPreviousQueryFromHistory(@TempDir Path dir) throws Exception {
        // Given — history seeded with a query (no terminator); ArrowUp recalls it.
        Path file = writeFixture(dir);
        QueryHistory history = QueryHistory.load(dir.resolve("hist"));
        history.add("select name from vtx.t");
        List<Key> script = new ArrayList<>();
        script.add(Key.ArrowUp.INSTANCE);          // recall into buffer
        script.addAll(type(";\n"));                // terminate -> run
        FakeTerminal term = new FakeTerminal(SIZE, script);

        // When
        runWith(term, file, history);

        // Then — the recalled query executes.
        assertThat(term.output()).contains("alice");
    }

    @Test
    void run_tabFocusesResultGridAfterQuery(@TempDir Path dir) throws Exception {
        // Given — run a query, then Tab to move focus into the result grid.
        Path file = writeFixture(dir);
        List<Key> script = new ArrayList<>(type("select name from vtx.t;\n"));
        script.add(new Key.Char('\t'));
        FakeTerminal term = new FakeTerminal(SIZE, script);

        // When
        runWith(term, file, QueryHistory.load(dir.resolve("hist")));

        // Then — the divider reflects the results focus.
        assertThat(term.output()).contains("[results]");
    }

    @Test
    void run_invalidSql_reportsErrorWithoutCrashing(@TempDir Path dir) throws Exception {
        // Given — a query against a non-existent column.
        Path file = writeFixture(dir);
        FakeTerminal term = new FakeTerminal(SIZE, type("select nope from vtx.t;\n"));

        // When
        runWith(term, file, QueryHistory.load(dir.resolve("hist")));

        // Then — the status line shows an error and the loop exits cleanly.
        assertThat(term.output()).contains("ERROR:");
        assertThat(term.isClosed()).isFalse(); // run() does not close the caller's terminal
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void runWith(FakeTerminal term, Path file, QueryHistory history) throws Exception {
        try (Connection conn = connect(file)) {
            VortexSqlTui.run(term, conn, List.of("t"), history);
        }
    }

    private static Connection connect(Path file) throws SQLException {
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");
        Connection conn = DriverManager.getConnection("jdbc:calcite:", info);
        conn.unwrap(CalciteConnection.class).getRootSchema()
                .add("vtx", new VortexSchema(Map.of("t", file)));
        return conn;
    }

    private static List<Key> type(String text) {
        List<Key> keys = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            keys.add(c == '\n' ? Key.Enter.INSTANCE : new Key.Char(c));
        }
        return keys;
    }

    private static Path writeFixture(Path dir) throws IOException {
        Path file = dir.resolve("t.vortex");
        DType.Struct schema = new DType.Struct(List.of("name"), List.of(DType.UTF8), false);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of("name", new String[]{"alice", "bob", "carol"}));
        }
        return file;
    }
}
