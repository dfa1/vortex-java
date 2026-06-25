package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.calcite.VortexSchema;
import io.github.dfa1.vortex.cli.tui.term.Ansi;
import io.github.dfa1.vortex.cli.tui.term.Key;
import io.github.dfa1.vortex.cli.tui.term.Terminal;

import org.apache.calcite.jdbc.CalciteConnection;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/// Interactive SQL shell over one or more Vortex files, backed by Calcite.
///
/// The screen is split into a multiline editor (top) and a scrollable result grid (bottom). The
/// editor follows the psql convention: `Enter` inserts a newline and a query runs only when the
/// accumulated text ends with `;`. `Tab` moves focus between the editor and the result grid;
/// `Up`/`Down` at the editor's top/bottom edge recall previous queries from [QueryHistory].
/// `Ctrl-D` quits.
///
/// Queries execute synchronously on the render thread - the UI briefly shows `running…` and then
/// repaints with the result. Vortex readers open and close inside the scan, so no separate I/O
/// thread is needed for arena confinement.
public final class VortexSqlTui {

    /// Calcite schema name the Vortex tables are registered under; queries read `vtx.tableName`.
    static final String SCHEMA = "vtx";

    /// Maximum rows materialised from a result set into the grid; excess rows are dropped and the
    /// status bar flags the truncation.
    static final int MAX_RESULT_ROWS = 10_000;

    private VortexSqlTui() {
    }

    /// Opens the shell over `tables`, loading and persisting history at the default location.
    ///
    /// @param tables map from SQL table name to backing `.vortex` file
    /// @throws IOException if Calcite setup or terminal initialisation fails
    public static void show(Map<String, Path> tables) throws IOException {
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");
        try (Connection conn = DriverManager.getConnection("jdbc:calcite:", info)) {
            conn.unwrap(CalciteConnection.class).getRootSchema().add(SCHEMA, new VortexSchema(tables));
            try (Terminal term = Terminal.open()) {
                run(term, conn, tables.keySet(), QueryHistory.loadDefault());
            }
        } catch (SQLException e) {
            throw new IOException("cannot initialise Calcite SQL shell", e);
        }
    }

    /// Runs the shell loop against a caller-supplied terminal and connection. Package-private seam
    /// for tests that drive a scripted [FakeTerminal] over an in-memory Calcite connection.
    ///
    /// @param term    terminal to render to and read keys from
    /// @param conn    Calcite connection with the Vortex schema already registered
    /// @param tables  table names, shown in the title bar
    /// @param history recall buffer, updated as queries run
    /// @throws IOException if a terminal write fails
    static void run(Terminal term, Connection conn, Iterable<String> tables, QueryHistory history)
            throws IOException {
        new Loop(term, conn, tableLabel(tables), history).run();
    }

    private static String tableLabel(Iterable<String> tables) {
        StringBuilder sb = new StringBuilder();
        for (String t : tables) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(SCHEMA).append('.').append(t);
        }
        return sb.toString();
    }

    private enum Focus { EDITOR, RESULTS }

    private static final class Loop {

        private static final int CTRL_D = 4;
        private static final int GUTTER = 5;
        private static final int MIN_COL_WIDTH = 4;
        private static final int MAX_COL_WIDTH = 32;

        private final Terminal term;
        private final Connection conn;
        private final String tableLabel;
        private final QueryHistory history;

        // Editor buffer: always at least one line. Cursor is (line, column).
        private final List<StringBuilder> lines = new ArrayList<>(List.of(new StringBuilder()));
        private int cursorLine;
        private int cursorCol;
        private int editorOffset;

        // History recall: index into history.entries(), or -1 when editing a fresh buffer.
        private int historyIndex = -1;

        private Focus focus = Focus.EDITOR;
        private ResultGrid result;
        private long resultRowOffset;
        private int resultColOffset;
        private String status = "Enter SQL; end with ';' to run.  Tab: focus  Ctrl-D: quit";
        private boolean dirty = true;

        Loop(Terminal term, Connection conn, String tableLabel, QueryHistory history) {
            this.term = term;
            this.conn = conn;
            this.tableLabel = tableLabel;
            this.history = history;
        }

        void run() throws IOException {
            term.write(Ansi.ENTER_ALT_SCREEN);
            term.write(Ansi.CLEAR_SCREEN);
            try {
                while (true) {
                    if (dirty) {
                        render();
                        dirty = false;
                    }
                    Key key = term.readKey();
                    if (!handle(key)) {
                        return;
                    }
                }
            } finally {
                term.write(Ansi.SHOW_CURSOR);
                term.write(Ansi.EXIT_ALT_SCREEN);
                term.flush();
            }
        }

        private boolean handle(Key key) throws IOException {
            if (key instanceof Key.Eof) {
                return false;
            }
            if (key instanceof Key.Char c && c.value() == CTRL_D) {
                return false;
            }
            if (focus == Focus.RESULTS) {
                handleResults(key);
                return true;
            }
            return handleEditor(key);
        }

        private boolean handleEditor(Key key) throws IOException {
            switch (key) {
                case Key.Char c when c.value() == '\t' -> focusResults();
                case Key.Char c -> insertChar(c.value());
                case Key.Enter _ -> onEnter();
                case Key.Backspace _ -> backspace();
                case Key.ArrowLeft _ -> moveLeft();
                case Key.ArrowRight _ -> moveRight();
                case Key.ArrowUp _ -> upOrHistory();
                case Key.ArrowDown _ -> downOrHistory();
                case Key.Escape _ -> clearBuffer();
                default -> { /* ignore other keys in the editor */ }
            }
            return true;
        }

        private void handleResults(Key key) {
            if (result == null) {
                focus = Focus.EDITOR;
                dirty = true;
                return;
            }
            switch (key) {
                case Key.ArrowUp _ -> scrollResult(-1);
                case Key.ArrowDown _ -> scrollResult(1);
                case Key.PageUp _ -> scrollResult(-pageRows());
                case Key.PageDown _ -> scrollResult(pageRows());
                case Key.ArrowLeft _ -> scrollResultCol(-1);
                case Key.ArrowRight _ -> scrollResultCol(1);
                case Key.Char c when c.value() == '\t' -> {
                    focus = Focus.EDITOR;
                    dirty = true;
                }
                case Key.Escape _ -> {
                    focus = Focus.EDITOR;
                    dirty = true;
                }
                default -> { /* ignore */ }
            }
        }

        // ---- editor editing ---------------------------------------------------------------

        private void insertChar(char c) {
            lines.get(cursorLine).insert(cursorCol, c);
            cursorCol++;
            historyIndex = -1;
            dirty = true;
        }

        private void onEnter() throws IOException {
            if (bufferText().strip().endsWith(";")) {
                runQuery();
                return;
            }
            StringBuilder cur = lines.get(cursorLine);
            String tail = cur.substring(cursorCol);
            cur.setLength(cursorCol);
            lines.add(cursorLine + 1, new StringBuilder(tail));
            cursorLine++;
            cursorCol = 0;
            historyIndex = -1;
            dirty = true;
        }

        private void backspace() {
            StringBuilder cur = lines.get(cursorLine);
            if (cursorCol > 0) {
                cur.deleteCharAt(cursorCol - 1);
                cursorCol--;
            } else if (cursorLine > 0) {
                StringBuilder prev = lines.get(cursorLine - 1);
                cursorCol = prev.length();
                prev.append(cur);
                lines.remove(cursorLine);
                cursorLine--;
            }
            historyIndex = -1;
            dirty = true;
        }

        private void moveLeft() {
            if (cursorCol > 0) {
                cursorCol--;
            } else if (cursorLine > 0) {
                cursorLine--;
                cursorCol = lines.get(cursorLine).length();
            }
            dirty = true;
        }

        private void moveRight() {
            if (cursorCol < lines.get(cursorLine).length()) {
                cursorCol++;
            } else if (cursorLine < lines.size() - 1) {
                cursorLine++;
                cursorCol = 0;
            }
            dirty = true;
        }

        private void upOrHistory() {
            if (cursorLine > 0) {
                cursorLine--;
                cursorCol = Math.min(cursorCol, lines.get(cursorLine).length());
                dirty = true;
            } else {
                recallOlder();
            }
        }

        private void downOrHistory() {
            if (cursorLine < lines.size() - 1) {
                cursorLine++;
                cursorCol = Math.min(cursorCol, lines.get(cursorLine).length());
                dirty = true;
            } else {
                recallNewer();
            }
        }

        private void clearBuffer() {
            lines.clear();
            lines.add(new StringBuilder());
            cursorLine = 0;
            cursorCol = 0;
            editorOffset = 0;
            historyIndex = -1;
            dirty = true;
        }

        // ---- history recall ---------------------------------------------------------------

        private void recallOlder() {
            List<String> entries = history.entries();
            if (entries.isEmpty()) {
                return;
            }
            int next = historyIndex < 0 ? entries.size() - 1 : historyIndex - 1;
            if (next < 0) {
                return;
            }
            historyIndex = next;
            loadIntoBuffer(entries.get(next));
        }

        private void recallNewer() {
            List<String> entries = history.entries();
            if (historyIndex < 0) {
                return;
            }
            int next = historyIndex + 1;
            if (next >= entries.size()) {
                historyIndex = -1;
                clearBuffer();
                return;
            }
            historyIndex = next;
            loadIntoBuffer(entries.get(next));
        }

        private void loadIntoBuffer(String text) {
            lines.clear();
            for (String line : text.split("\n", -1)) {
                lines.add(new StringBuilder(line));
            }
            cursorLine = lines.size() - 1;
            cursorCol = lines.get(cursorLine).length();
            editorOffset = 0;
            dirty = true;
        }

        // ---- query execution --------------------------------------------------------------

        private void runQuery() throws IOException {
            String sql = stripTrailingSemicolon(bufferText().strip());
            if (sql.isEmpty()) {
                status = "empty query";
                dirty = true;
                return;
            }
            history.add(bufferText().strip());
            historyIndex = -1;
            status = "running…";
            render();
            long start = System.nanoTime();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                result = ResultGrid.from(rs, MAX_RESULT_ROWS);
                resultRowOffset = 0;
                resultColOffset = 0;
                long ms = (System.nanoTime() - start) / 1_000_000;
                status = result.rowCount() + " row(s) in " + ms + " ms"
                        + (result.truncated() ? "  (showing first " + MAX_RESULT_ROWS + ")" : "");
                clearBuffer();
            } catch (SQLException | RuntimeException e) {
                // Calcite surfaces parse/validation failures as SQLException, but planning and
                // scan errors can bubble up as unchecked exceptions; keep the buffer so the user
                // can fix the query rather than losing it.
                status = "ERROR: " + rootMessage(e);
            }
            dirty = true;
        }

        private static String stripTrailingSemicolon(String s) {
            String t = s;
            while (t.endsWith(";")) {
                t = t.substring(0, t.length() - 1).stripTrailing();
            }
            return t;
        }

        private static String rootMessage(Throwable t) {
            Throwable cur = t;
            while (cur.getCause() != null && cur.getCause() != cur) {
                cur = cur.getCause();
            }
            String msg = cur.getMessage();
            return msg != null ? msg.split("\n", 2)[0] : cur.getClass().getSimpleName();
        }

        // ---- result scrolling -------------------------------------------------------------

        private void scrollResult(long delta) {
            long max = Math.max(0, (long) result.rowCount() - 1);
            resultRowOffset = clamp(resultRowOffset + delta, 0, max);
            dirty = true;
        }

        private void scrollResultCol(int delta) {
            int max = Math.max(0, result.columns().size() - 1);
            resultColOffset = clamp(resultColOffset + delta, 0, max);
            dirty = true;
        }

        private void focusResults() {
            if (result != null) {
                focus = Focus.RESULTS;
                dirty = true;
            }
        }

        private long pageRows() {
            return Math.max(1, term.size().rows() / 2);
        }

        // ---- rendering --------------------------------------------------------------------

        private String bufferText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(lines.get(i));
            }
            return sb.toString();
        }

        private void render() throws IOException {
            Terminal.Size size = term.size();
            int rows = size.rows();
            int cols = size.cols();
            if (cols < 20 || rows < 8) {
                term.write(Ansi.CLEAR_SCREEN);
                term.write(Ansi.CURSOR_HOME);
                term.write("terminal too small");
                term.flush();
                return;
            }
            int editorRows = clamp(lines.size(), 1, Math.max(1, rows - 7));
            ensureEditorVisible(editorRows);
            int resultRows = Math.max(0, rows - editorRows - 4);

            StringBuilder out = new StringBuilder(cols * rows);
            out.append(Ansi.HIDE_CURSOR).append(Ansi.CURSOR_HOME);
            renderTitle(out, cols);
            renderEditor(out, editorRows, cols);
            renderDivider(out, cols);
            renderResults(out, resultRows, cols);
            renderStatus(out, cols);

            term.write(out.toString());
            if (focus == Focus.EDITOR) {
                int screenRow = 2 + (cursorLine - editorOffset);
                int screenCol = GUTTER + cursorCol + 1;
                term.write(Ansi.moveTo(screenRow, screenCol));
                term.write(Ansi.SHOW_CURSOR);
            }
            term.flush();
        }

        private void ensureEditorVisible(int editorRows) {
            if (cursorLine < editorOffset) {
                editorOffset = cursorLine;
            } else if (cursorLine >= editorOffset + editorRows) {
                editorOffset = cursorLine - editorRows + 1;
            }
        }

        private void renderTitle(StringBuilder out, int cols) {
            out.append(Ansi.bg(44)).append(Ansi.fg(97));
            appendPadded(out, " Vortex SQL  " + tableLabel, cols);
            out.append(Ansi.RESET).append("\r\n");
        }

        private void renderEditor(StringBuilder out, int editorRows, int cols) {
            for (int r = 0; r < editorRows; r++) {
                int absLine = editorOffset + r;
                String gutter = absLine == 0 ? "sql> " : "  |  ";
                String text = absLine < lines.size() ? lines.get(absLine).toString() : "";
                out.append(Ansi.fg(90)).append(gutter).append(Ansi.RESET);
                appendPadded(out, truncate(text, cols - GUTTER), cols - GUTTER);
                out.append("\r\n");
            }
        }

        private void renderDivider(StringBuilder out, int cols) {
            String focusLabel = focus == Focus.EDITOR ? " [editor] " : " [results] ";
            out.append(Ansi.bg(100)).append(Ansi.fg(97));
            appendPadded(out, focusLabel + status, cols);
            out.append(Ansi.RESET).append("\r\n");
        }

        private void renderResults(StringBuilder out, int resultRows, int cols) {
            if (resultRows <= 0) {
                return;
            }
            if (result == null) {
                for (int r = 0; r < resultRows; r++) {
                    appendPadded(out, r == 0 ? "  (no results yet)" : "", cols);
                    out.append("\r\n");
                }
                return;
            }
            int[] widths = colWidths(cols);
            renderResultHeader(out, widths, cols);
            int dataRows = resultRows - 1;
            for (int r = 0; r < dataRows; r++) {
                long abs = resultRowOffset + r;
                if (abs >= result.rowCount()) {
                    appendPadded(out, "", cols);
                    out.append("\r\n");
                    continue;
                }
                renderResultRow(out, result.rows().get((int) abs), widths, cols);
                out.append("\r\n");
            }
        }

        private void renderResultHeader(StringBuilder out, int[] widths, int cols) {
            StringBuilder line = new StringBuilder();
            List<String> columns = result.columns();
            int width = 0;
            for (int c = resultColOffset; c < columns.size() && width + widths[c] + 1 <= cols; c++) {
                line.append(padRight(truncate(columns.get(c), widths[c]), widths[c])).append(' ');
                width += widths[c] + 1;
            }
            out.append(Ansi.bg(238)).append(Ansi.fg(97));
            appendPadded(out, line.toString(), cols);
            out.append(Ansi.RESET).append("\r\n");
        }

        private void renderResultRow(StringBuilder out, String[] row, int[] widths, int cols) {
            StringBuilder line = new StringBuilder();
            int width = 0;
            for (int c = resultColOffset; c < widths.length && width + widths[c] + 1 <= cols; c++) {
                String cell = c < row.length ? row[c] : "";
                line.append(padRight(truncate(cell, widths[c]), widths[c])).append(' ');
                width += widths[c] + 1;
            }
            appendPadded(out, line.toString(), cols);
        }

        private void renderStatus(StringBuilder out, int cols) {
            String hint = focus == Focus.EDITOR
                    ? " Enter: newline  ;Enter: run  ↑↓: history  Tab: results  Ctrl-D: quit "
                    : " ↑↓←→/PgUp/PgDn: scroll  Tab/Esc: editor  Ctrl-D: quit ";
            out.append(Ansi.bg(44)).append(Ansi.fg(97));
            appendPadded(out, hint, cols);
            out.append(Ansi.RESET);
        }

        private int[] colWidths(int cols) {
            List<String> columns = result.columns();
            int n = columns.size();
            int[] widths = new int[n];
            int sample = Math.min(result.rowCount(), 50);
            for (int c = 0; c < n; c++) {
                int w = columns.get(c).length();
                for (int r = 0; r < sample; r++) {
                    String[] row = result.rows().get(r);
                    if (c < row.length && row[c] != null) {
                        w = Math.max(w, row[c].length());
                    }
                }
                widths[c] = Math.min(MAX_COL_WIDTH, Math.max(MIN_COL_WIDTH, Math.min(w, cols)));
            }
            return widths;
        }

        // ---- small helpers ----------------------------------------------------------------

        private static long clamp(long v, long lo, long hi) {
            if (v < lo) {
                return lo;
            }
            return Math.min(v, hi);
        }

        private static int clamp(int v, int lo, int hi) {
            if (v < lo) {
                return lo;
            }
            return Math.min(v, hi);
        }

        private static String truncate(String s, int width) {
            if (s == null) {
                return "";
            }
            if (width <= 0) {
                return "";
            }
            if (s.length() <= width) {
                return s;
            }
            if (width == 1) {
                return "…";
            }
            return s.substring(0, width - 1) + "…";
        }

        private static String padRight(String s, int width) {
            if (s.length() >= width) {
                return s;
            }
            StringBuilder sb = new StringBuilder(width);
            sb.append(s);
            while (sb.length() < width) {
                sb.append(' ');
            }
            return sb.toString();
        }

        private static void appendPadded(StringBuilder out, String s, int width) {
            if (width <= 0) {
                return;
            }
            int written = Math.min(s.length(), width);
            out.append(s, 0, written);
            for (int i = written; i < width; i++) {
                out.append(' ');
            }
        }
    }
}
