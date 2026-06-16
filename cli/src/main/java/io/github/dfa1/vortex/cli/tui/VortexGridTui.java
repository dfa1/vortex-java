package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.cli.tui.term.Ansi;
import io.github.dfa1.vortex.cli.tui.term.Key;
import io.github.dfa1.vortex.cli.tui.term.Terminal;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/// Excel-like scrollable grid viewer for a Vortex file's rows.
///
/// Renders a frozen header row and a viewport of data rows that the user can
/// scroll with the arrow keys. Quit with `q` or `Esc`.
public final class VortexGridTui {

    private VortexGridTui() {
    }

    /// Opens the terminal in raw mode and runs the grid viewer until quit.
    ///
    /// @param data pre-materialised rows + column names + source label
    /// @throws IOException if the terminal cannot be initialized
    public static void show(GridData data) throws IOException {
        try (Terminal term = Terminal.open()) {
            new Loop(term, data).run();
        }
    }

    /// Pre-materialised grid contents.
    ///
    /// @param source         display label for the file (path or URL)
    /// @param columns        column header names in display order
    /// @param rows           row data; each entry has `columns.size()` cells
    /// @param totalRows      logical row count in the file (may exceed `rows.size()`)
    /// @param truncated      true if `rows` is a prefix of the full file
    public record GridData(String source, List<String> columns, List<String[]> rows,
                           long totalRows, boolean truncated) {
    }

    private static final class Loop {

        private static final int MIN_COL_WIDTH = 4;
        private static final int MAX_COL_WIDTH = 24;
        private static final int ROW_INDEX_PAD = 2;
        private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

        private final Terminal term;
        private final GridData data;
        private final int[] colWidths;
        private final int rowNumberWidth;

        private int cursorRow;
        private int cursorCol;
        private int rowOffset;
        private int colOffset;
        private boolean dirty = true;

        Loop(Terminal term, GridData data) {
            this.term = term;
            this.data = data;
            this.colWidths = computeColWidths(data);
            this.rowNumberWidth = Math.max(3, Long.toString(data.totalRows()).length()) + ROW_INDEX_PAD;
        }

        void run() throws IOException {
            term.write(Ansi.ENTER_ALT_SCREEN);
            term.write(Ansi.HIDE_CURSOR);
            try {
                while (true) {
                    if (dirty) {
                        render();
                        dirty = false;
                    }
                    Optional<Key> maybe = term.readKey(POLL_INTERVAL);
                    if (maybe.isEmpty()) {
                        continue;
                    }
                    if (!handle(maybe.get())) {
                        return;
                    }
                }
            } finally {
                term.write(Ansi.SHOW_CURSOR);
                term.write(Ansi.EXIT_ALT_SCREEN);
                term.flush();
            }
        }

        private boolean handle(Key key) {
            return switch (key) {
                case Key.ArrowUp ignored -> move(-1, 0);
                case Key.ArrowDown ignored -> move(1, 0);
                case Key.ArrowLeft ignored -> move(0, -1);
                case Key.ArrowRight ignored -> move(0, 1);
                case Key.PageUp ignored -> move(-pageRows(), 0);
                case Key.PageDown ignored -> move(pageRows(), 0);
                case Key.Home ignored -> jumpCol(0);
                case Key.End ignored -> jumpCol(data.columns().size() - 1);
                case Key.Char c when c.value() == 'g' -> jumpRow(0);
                case Key.Char c when c.value() == 'G' -> jumpRow(data.rows().size() - 1);
                case Key.Char c when c.value() == 'q' -> false;
                case Key.Escape ignored -> false;
                case Key.Eof ignored -> false;
                default -> true;
            };
        }

        private boolean move(int dr, int dc) {
            int rows = data.rows().size();
            int cols = data.columns().size();
            if (rows == 0 || cols == 0) {
                return true;
            }
            int newRow = clamp(cursorRow + dr, 0, rows - 1);
            int newCol = clamp(cursorCol + dc, 0, cols - 1);
            if (newRow != cursorRow || newCol != cursorCol) {
                cursorRow = newRow;
                cursorCol = newCol;
                dirty = true;
            }
            return true;
        }

        private boolean jumpRow(int row) {
            int rows = data.rows().size();
            if (rows == 0) {
                return true;
            }
            cursorRow = clamp(row, 0, rows - 1);
            dirty = true;
            return true;
        }

        private boolean jumpCol(int col) {
            int cols = data.columns().size();
            if (cols == 0) {
                return true;
            }
            cursorCol = clamp(col, 0, cols - 1);
            dirty = true;
            return true;
        }

        private int pageRows() {
            Terminal.Size size = term.size();
            return Math.max(1, size.rows() - 4);
        }

        private void render() throws IOException {
            Terminal.Size size = term.size();
            int totalCols = size.cols();
            int totalRows = size.rows();
            if (totalCols <= rowNumberWidth + 2 || totalRows <= 3) {
                term.write(Ansi.CLEAR_SCREEN);
                term.write(Ansi.CURSOR_HOME);
                term.write("terminal too small");
                term.flush();
                return;
            }
            ensureCursorVisible(totalRows, totalCols);

            StringBuilder out = new StringBuilder(totalCols * totalRows);
            out.append(Ansi.CLEAR_SCREEN).append(Ansi.CURSOR_HOME);
            renderTitle(out, totalCols);
            renderHeader(out, totalCols);
            renderRows(out, totalRows, totalCols);
            renderStatus(out, totalRows, totalCols);

            term.write(out.toString());
            term.flush();
        }

        private void ensureCursorVisible(int totalRows, int totalCols) {
            int viewportRows = totalRows - 3;
            if (cursorRow < rowOffset) {
                rowOffset = cursorRow;
            } else if (cursorRow >= rowOffset + viewportRows) {
                rowOffset = cursorRow - viewportRows + 1;
            }
            int dataCols = totalCols - rowNumberWidth;
            while (cursorCol < colOffset) {
                colOffset = cursorCol;
            }
            while (true) {
                int width = 0;
                int c = colOffset;
                while (c < data.columns().size() && width + colWidths[c] + 1 <= dataCols) {
                    width += colWidths[c] + 1;
                    if (c == cursorCol) {
                        return;
                    }
                    c++;
                }
                if (cursorCol < c) {
                    return;
                }
                if (colOffset >= cursorCol) {
                    return;
                }
                colOffset++;
            }
        }

        private void renderTitle(StringBuilder out, int totalCols) {
            String title = " Vortex View  " + data.source()
                    + "  rows " + data.rows().size() + "/" + data.totalRows()
                    + (data.truncated() ? " (truncated)" : "")
                    + "  cols " + data.columns().size();
            out.append(Ansi.bg(44)).append(Ansi.fg(97));
            appendPadded(out, title, totalCols);
            out.append(Ansi.RESET).append("\r\n");
        }

        private void renderHeader(StringBuilder out, int totalCols) {
            StringBuilder line = new StringBuilder(totalCols);
            appendPadded(line, padRight("#", rowNumberWidth - 1) + " ", rowNumberWidth);
            int width = rowNumberWidth;
            for (int c = colOffset;
                 c < data.columns().size() && width + colWidths[c] + 1 <= totalCols;
                 c++) {
                String cell = truncate(data.columns().get(c), colWidths[c]);
                line.append(padRight(cell, colWidths[c])).append(' ');
                width += colWidths[c] + 1;
            }
            out.append(Ansi.bg(100)).append(Ansi.fg(97));
            appendPadded(out, line.toString(), totalCols);
            out.append(Ansi.RESET).append("\r\n");
        }

        private void renderRows(StringBuilder out, int totalRows, int totalCols) {
            int viewportRows = totalRows - 3;
            int rowCount = data.rows().size();
            for (int r = 0; r < viewportRows; r++) {
                int absRow = rowOffset + r;
                if (absRow >= rowCount) {
                    appendPadded(out, "", totalCols);
                    out.append("\r\n");
                    continue;
                }
                renderDataRow(out, absRow, totalCols);
                out.append("\r\n");
            }
        }

        private void renderDataRow(StringBuilder out, int absRow, int totalCols) {
            boolean cursorRowLine = absRow == cursorRow;
            String rowLabel = padRight(Integer.toString(absRow + 1), rowNumberWidth - 1) + " ";
            if (cursorRowLine) {
                out.append(Ansi.fg(93));
            } else {
                out.append(Ansi.fg(90));
            }
            out.append(rowLabel).append(Ansi.RESET);

            int width = rowNumberWidth;
            String[] row = data.rows().get(absRow);
            for (int c = colOffset;
                 c < data.columns().size() && width + colWidths[c] + 1 <= totalCols;
                 c++) {
                String cell = c < row.length ? row[c] : "";
                String text = padRight(truncate(cell, colWidths[c]), colWidths[c]);
                boolean cursorCell = cursorRowLine && c == cursorCol;
                if (cursorCell) {
                    out.append(Ansi.bg(46)).append(Ansi.fg(30));
                } else if (cursorRowLine) {
                    out.append(Ansi.bg(236));
                }
                out.append(text);
                if (cursorCell || cursorRowLine) {
                    out.append(Ansi.RESET);
                }
                out.append(' ');
                width += colWidths[c] + 1;
            }
            if (width < totalCols) {
                appendPadded(out, "", totalCols - width);
            }
        }

        private void renderStatus(StringBuilder out, int totalRows, int totalCols) {
            String col = cursorCol < data.columns().size() ? data.columns().get(cursorCol) : "-";
            String cellValue = "";
            if (cursorRow < data.rows().size() && cursorCol < data.rows().get(cursorRow).length) {
                cellValue = data.rows().get(cursorRow)[cursorCol];
            }
            String right = " arrows/PgUp/PgDn move  g/G top/bot  q quit ";
            int rightRoom = right.length() < totalCols ? right.length() : 0;
            int leftBudget = totalCols - rightRoom;
            String leftFull = " R" + (cursorRow + 1) + " C" + (cursorCol + 1)
                    + "  " + col + " = " + cellValue;
            String left = truncate(leftFull, Math.max(0, leftBudget));

            StringBuilder line = new StringBuilder(totalCols);
            line.append(left);
            for (int i = line.length(); i < leftBudget; i++) {
                line.append(' ');
            }
            if (rightRoom > 0) {
                line.append(right);
            }
            out.append(Ansi.bg(44)).append(Ansi.fg(97));
            appendPadded(out, line.toString(), totalCols);
            out.append(Ansi.RESET);
        }

        private static int[] computeColWidths(GridData data) {
            int n = data.columns().size();
            int[] widths = new int[n];
            for (int c = 0; c < n; c++) {
                int w = Math.max(MIN_COL_WIDTH, data.columns().get(c).length());
                widths[c] = Math.min(MAX_COL_WIDTH, w);
            }
            int sampled = Math.min(data.rows().size(), 200);
            for (int r = 0; r < sampled; r++) {
                String[] row = data.rows().get(r);
                for (int c = 0; c < n && c < row.length; c++) {
                    if (row[c] == null) {
                        continue;
                    }
                    int len = row[c].length();
                    if (len > widths[c]) {
                        widths[c] = Math.min(MAX_COL_WIDTH, len);
                    }
                }
            }
            return widths;
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
            if (s.length() <= width) {
                return s;
            }
            if (width <= 1) {
                return s.substring(0, width);
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
