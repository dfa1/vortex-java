package io.github.dfa1.vortex.cli.tui;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/// An in-memory snapshot of a SQL [ResultSet]: column names plus up to a caller-supplied cap of
/// already-stringified cells, ready to feed the scrollable grid renderer.
///
/// Results are materialised eagerly (Vortex readers are confined to the scanning thread and closed
/// when the query enumerator finishes, so the rows must be copied out before the result set is
/// drained). When the underlying result exceeds the cap, [#truncated()] is `true` and only the
/// first `maxRows` rows are kept.
public final class ResultGrid {

    private final List<String> columns;
    private final List<String[]> rows;
    private final boolean truncated;

    private ResultGrid(List<String> columns, List<String[]> rows, boolean truncated) {
        this.columns = columns;
        this.rows = rows;
        this.truncated = truncated;
    }

    /// Drains `rs` into a snapshot, keeping at most `maxRows` rows.
    ///
    /// @param rs      the result set to read (left at end-of-cursor or at the first dropped row)
    /// @param maxRows the maximum number of rows to retain
    /// @return the materialised grid
    /// @throws SQLException if reading column metadata or a cell fails
    public static ResultGrid from(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>(colCount);
        for (int c = 1; c <= colCount; c++) {
            columns.add(meta.getColumnLabel(c));
        }
        List<String[]> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            String[] row = new String[colCount];
            for (int c = 1; c <= colCount; c++) {
                Object v = rs.getObject(c);
                row[c - 1] = v == null ? "" : v.toString();
            }
            rows.add(row);
        }
        return new ResultGrid(List.copyOf(columns), List.copyOf(rows), truncated);
    }

    /// Column labels, left to right.
    ///
    /// @return an unmodifiable list of column names
    public List<String> columns() {
        return columns;
    }

    /// The retained rows, each a per-column array of display strings.
    ///
    /// @return an unmodifiable list of rows
    public List<String[]> rows() {
        return rows;
    }

    /// Number of retained rows.
    ///
    /// @return the row count (at most the cap)
    public int rowCount() {
        return rows.size();
    }

    /// Whether the original result had more rows than were retained.
    ///
    /// @return true if rows were dropped at the cap
    public boolean truncated() {
        return truncated;
    }
}
