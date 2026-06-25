package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.cli.tui.term.Ansi;
import io.github.dfa1.vortex.cli.tui.term.Key;
import io.github.dfa1.vortex.cli.tui.term.Terminal;

import java.io.IOException;
import java.util.List;

/// Excel-like scrollable grid viewer over a [LazyGridSource].
///
/// Rows are decoded on demand as the viewport scrolls into new chunks. Quit
/// with `q` or `Esc`.
public final class VortexGridTui {

    private VortexGridTui() {
    }

    /// Opens the terminal in raw mode and runs the grid viewer until quit.
    ///
    /// @param source display label for the file (path or URL)
    /// @param data   lazy row source; closed by the caller
    /// @throws IOException if the terminal cannot be initialized or the row
    ///                     decoder fails
    public static void show(String source, LazyGridSource data) throws IOException {
        try (Terminal term = Terminal.open()) {
            run(term, source, data);
        }
    }

    /// Runs the grid viewer against a caller-supplied terminal. Package-private
    /// seam used by tests to drive the loop with a scripted fake terminal,
    /// bypassing the OS raw-mode setup in [#show(String, LazyGridSource)].
    ///
    /// @param term   terminal to render to and read keys from
    /// @param source display label for the file (path or URL)
    /// @param data   lazy row source; closed by the caller
    /// @throws IOException if the terminal write or row decode fails
    static void run(Terminal term, String source, LazyGridSource data) throws IOException {
        new Loop(term, source, data).run();
    }

    private static final class Loop {

        private static final int MIN_COL_WIDTH = 4;
        private static final int MAX_COL_WIDTH = 24;
        private static final int ROW_INDEX_PAD = 2;

        private final Terminal term;
        private final String source;
        private final LazyGridSource data;
        private final int[] colWidths;
        private final int rowNumberWidth;
        private final long totalRows;
        private final int totalCols;

        private long cursorRow;
        private int cursorCol;
        private long rowOffset;
        private int colOffset;
        private boolean dirty = true;
        private String errorMessage;

        Loop(Terminal term, String source, LazyGridSource data) {
            this.term = term;
            this.source = source;
            this.data = data;
            this.totalRows = data.totalRows();
            this.totalCols = data.columns().size();
            this.colWidths = computeColWidths(data.columns());
            this.rowNumberWidth = Math.max(3, Long.toString(Math.max(1, totalRows)).length()) + ROW_INDEX_PAD;
        }

        void run() throws IOException {
            term.write(Ansi.ENTER_ALT_SCREEN);
            term.write(Ansi.HIDE_CURSOR);
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

        private boolean handle(Key key) {
            return switch (key) {
                case Key.ArrowUp _ -> {
                    move(-1, 0);
                    yield true;
                }
                case Key.ArrowDown _ -> {
                    move(1, 0);
                    yield true;
                }
                case Key.ArrowLeft _ -> {
                    move(0, -1);
                    yield true;
                }
                case Key.ArrowRight _ -> {
                    move(0, 1);
                    yield true;
                }
                case Key.PageUp _ -> {
                    move(-pageRows(), 0);
                    yield true;
                }
                case Key.PageDown _ -> {
                    move(pageRows(), 0);
                    yield true;
                }
                case Key.Home _ -> {
                    jumpCol(0);
                    yield true;
                }
                case Key.End _ -> {
                    jumpCol(totalCols - 1);
                    yield true;
                }
                case Key.Char c when c.value() == 'g' -> {
                    jumpRow(0);
                    yield true;
                }
                case Key.Char c when c.value() == 'G' -> {
                    jumpRow(totalRows - 1);
                    yield true;
                }
                case Key.Char c when c.value() == 'q' -> false;
                case Key.Escape _ -> false;
                case Key.Eof _ -> false;
                default -> true;
            };
        }

        private void move(long dr, int dc) {
            if (totalRows == 0 || totalCols == 0) {
                return;
            }
            long newRow = clamp(cursorRow + dr, 0L, totalRows - 1);
            int newCol = clamp(cursorCol + dc, 0, totalCols - 1);
            if (newRow != cursorRow || newCol != cursorCol) {
                cursorRow = newRow;
                cursorCol = newCol;
                dirty = true;
            }
        }

        private void jumpRow(long row) {
            if (totalRows == 0) {
                return;
            }
            cursorRow = clamp(row, 0L, totalRows - 1);
            dirty = true;
        }

        private void jumpCol(int col) {
            if (totalCols == 0) {
                return;
            }
            cursorCol = clamp(col, 0, totalCols - 1);
            dirty = true;
        }

        private long pageRows() {
            Terminal.Size size = term.size();
            return Math.max(1, size.rows() - 4);
        }

        private void render() throws IOException {
            Terminal.Size size = term.size();
            int termCols = size.cols();
            int termRows = size.rows();
            if (termCols <= rowNumberWidth + 2 || termRows <= 3) {
                term.write(Ansi.CLEAR_SCREEN);
                term.write(Ansi.CURSOR_HOME);
                term.write("terminal too small");
                term.flush();
                return;
            }
            ensureCursorVisible(termRows, termCols);

            int viewportRows = Math.max(0, termRows - 3);
            String[][] window = fetchWindow(rowOffset, viewportRows);

            StringBuilder out = new StringBuilder(termCols * termRows);
            out.append(Ansi.CURSOR_HOME);
            renderTitle(out, termCols);
            renderHeader(out, termCols);
            renderRows(out, window, viewportRows, termCols);
            renderStatus(out, window, viewportRows, termCols);

            term.write(out.toString());
            term.flush();
        }

        private String[][] fetchWindow(long startAbsRow, int viewportRows) {
            try {
                return data.readRows(startAbsRow, viewportRows);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                errorMessage = "interrupted";
                return placeholderWindow(viewportRows);
            } catch (RuntimeException e) {
                errorMessage = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "");
                return placeholderWindow(viewportRows);
            }
        }

        private String[][] placeholderWindow(int viewportRows) {
            String[][] w = new String[viewportRows][];
            for (int r = 0; r < viewportRows; r++) {
                w[r] = new String[totalCols];
                for (int c = 0; c < totalCols; c++) {
                    w[r][c] = "?";
                }
            }
            return w;
        }

        private void ensureCursorVisible(int termRows, int termCols) {
            long viewportRows = (long) termRows - 3;
            if (cursorRow < rowOffset) {
                rowOffset = cursorRow;
            } else if (cursorRow >= rowOffset + viewportRows) {
                rowOffset = cursorRow - viewportRows + 1;
            }
            int dataCols = termCols - rowNumberWidth;
            while (cursorCol < colOffset) {
                colOffset = cursorCol;
            }
            while (true) {
                int width = 0;
                int c = colOffset;
                while (c < totalCols && width + colWidths[c] + 1 <= dataCols) {
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

        private void renderTitle(StringBuilder out, int termCols) {
            String err = errorMessage;
            int curChunk = data.currentChunkIndex();
            String chunkLabel = curChunk >= 0
                    ? "  chunk " + (curChunk + 1) + "/" + data.chunkCount()
                    : "";
            String title = err != null
                    ? " Vortex View  " + source + "  ERROR: " + err
                    : " Vortex View  " + source
                            + "  rows " + totalRows
                            + "  cols " + totalCols
                            + chunkLabel;
            out.append(Ansi.bg(err != null ? 41 : 44)).append(Ansi.fg(97));
            appendPadded(out, title, termCols);
            out.append(Ansi.RESET).append("\r\n");
        }

        private void renderHeader(StringBuilder out, int termCols) {
            StringBuilder line = new StringBuilder(termCols);
            appendPadded(line, padRight("#", rowNumberWidth - 1) + " ", rowNumberWidth);
            int width = rowNumberWidth;
            for (int c = colOffset;
                 c < totalCols && width + colWidths[c] + 1 <= termCols;
                 c++) {
                String cell = truncate(data.columns().get(c), colWidths[c]);
                line.append(padRight(cell, colWidths[c])).append(' ');
                width += colWidths[c] + 1;
            }
            out.append(Ansi.bg(100)).append(Ansi.fg(97));
            appendPadded(out, line.toString(), termCols);
            out.append(Ansi.RESET).append("\r\n");
        }

        private void renderRows(StringBuilder out, String[][] window, int viewportRows, int termCols) {
            for (int r = 0; r < viewportRows; r++) {
                long absRow = rowOffset + r;
                if (absRow >= totalRows) {
                    appendPadded(out, "", termCols);
                    out.append("\r\n");
                    continue;
                }
                String[] row = window[r];
                renderDataRow(out, absRow, row, termCols);
                out.append("\r\n");
            }
        }

        private void renderDataRow(StringBuilder out, long absRow, String[] row, int termCols) {
            boolean cursorRowLine = absRow == cursorRow;
            String rowLabel = padRight(Long.toString(absRow + 1), rowNumberWidth - 1) + " ";
            if (cursorRowLine) {
                out.append(Ansi.fg(93));
            } else {
                out.append(Ansi.fg(90));
            }
            out.append(rowLabel).append(Ansi.RESET);

            int width = rowNumberWidth;
            for (int c = colOffset;
                 c < totalCols && width + colWidths[c] + 1 <= termCols;
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
            if (width < termCols) {
                appendPadded(out, "", termCols - width);
            }
        }

        private void renderStatus(StringBuilder out, String[][] window, int viewportRows, int termCols) {
            String col = cursorCol < totalCols ? data.columns().get(cursorCol) : "-";
            String cellValue = currentCellValue(window, viewportRows);
            String right = " arrows/PgUp/PgDn move  g/G top/bot  Esc/q quit ";
            int rightRoom = right.length() < termCols ? right.length() : 0;
            int leftBudget = termCols - rightRoom;
            String leftFull = " R" + (cursorRow + 1) + " C" + (cursorCol + 1)
                    + "  " + col + " = " + cellValue;
            String left = truncate(leftFull, Math.max(0, leftBudget));

            StringBuilder line = new StringBuilder(termCols);
            line.append(left);
            for (int i = line.length(); i < leftBudget; i++) {
                line.append(' ');
            }
            if (rightRoom > 0) {
                line.append(right);
            }
            out.append(Ansi.bg(44)).append(Ansi.fg(97));
            appendPadded(out, line.toString(), termCols);
            out.append(Ansi.RESET);
        }

        private String currentCellValue(String[][] window, int viewportRows) {
            if (totalRows == 0 || totalCols == 0) {
                return "";
            }
            int idx = (int) (cursorRow - rowOffset);
            if (idx >= 0 && idx < viewportRows && window[idx] != null
                    && cursorCol < window[idx].length && window[idx][cursorCol] != null) {
                return window[idx][cursorCol];
            }
            return "";
        }

        private static int[] computeColWidths(List<String> columns) {
            int n = columns.size();
            int[] widths = new int[n];
            for (int c = 0; c < n; c++) {
                int w = Math.max(MIN_COL_WIDTH, columns.get(c).length());
                widths[c] = Math.min(MAX_COL_WIDTH, w);
            }
            return widths;
        }

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
