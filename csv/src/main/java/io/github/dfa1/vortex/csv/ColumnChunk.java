package io.github.dfa1.vortex.csv;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// One chunk of CSV fields kept column by column as raw bytes: each column is one growing byte
/// buffer plus the end offset of each row's field, so parsing allocates nothing per field and each
/// column's conversion reads one contiguous buffer.
final class ColumnChunk {

    private static final int INITIAL_COLUMN_BYTES = 1 << 12;

    private byte[][] bytes = new byte[0][];
    private int[][] ends = new int[0][];
    private int[] used = new int[0];
    private final int capacity;
    private int rows;

    /// @param capacity the most rows the chunk holds
    ColumnChunk(int capacity) {
        this.capacity = capacity;
    }

    /// Appends `src[from, to)` to field `column` of the row being read; a field may arrive in
    /// several pieces (a quoted field around each escaped quote).
    void append(int column, byte[] src, int from, int to) {
        if (column >= used.length) {
            addColumns(column + 1);
        }
        int length = to - from;
        byte[] buffer = bytes[column];
        int at = used[column];
        if (buffer.length - at < length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, at + length));
            bytes[column] = buffer;
        }
        System.arraycopy(src, from, buffer, at, length);
        used[column] = at + length;
    }

    /// Ends field `column` of the row being read.
    void endField(int column) {
        if (column >= used.length) {
            addColumns(column + 1);
        }
        ends[column][rows] = used[column];
    }

    /// Ends the row being read.
    void endRow() {
        rows++;
    }

    /// Drops the fields of the row being read, which the input ended in the middle of.
    void abortRow() {
        for (int c = 0; c < used.length; c++) {
            used[c] = rows == 0 ? 0 : ends[c][rows - 1];
        }
    }

    /// The number of complete rows.
    int rows() {
        return rows;
    }

    /// Whether the chunk holds its capacity of rows.
    boolean isFull() {
        return rows == capacity;
    }

    /// The number of columns any row has had.
    int columns() {
        return used.length;
    }

    /// Column `column`'s bytes, or `null` for a column no row has.
    byte[] bytes(int column) {
        return column < bytes.length ? bytes[column] : null;
    }

    /// Where row `row`'s field starts in [#bytes(int)].
    int start(int column, int row) {
        return row == 0 ? 0 : ends[column][row - 1];
    }

    /// Where row `row`'s field ends in [#bytes(int)].
    int end(int column, int row) {
        return ends[column][row];
    }

    /// Row `row`'s field in column `column` as text, `""` for a column the row lacks; malformed
    /// UTF-8 decodes to U+FFFD as the JDK decoder does.
    String text(int column, int row) {
        if (column >= bytes.length) {
            return "";
        }
        int from = start(column, row);
        return new String(bytes[column], from, end(column, row) - from, StandardCharsets.UTF_8);
    }

    private void addColumns(int count) {
        int old = used.length;
        bytes = Arrays.copyOf(bytes, count);
        ends = Arrays.copyOf(ends, count);
        used = Arrays.copyOf(used, count);
        for (int c = old; c < count; c++) {
            bytes[c] = new byte[INITIAL_COLUMN_BYTES];
            ends[c] = new int[capacity];
        }
    }
}
