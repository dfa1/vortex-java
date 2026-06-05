package io.github.dfa1.vortex.jdbc;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.VortexWriter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Reads rows from a JDBC {@link ResultSet} and writes a Vortex file.
///
/// The schema is derived from {@link ResultSetMetaData} — no type inference is needed.
/// SQL NULL values are mapped to {@code 0}, {@code 0.0}, {@code false}, or {@code ""} depending on column type.
/// Use {@link JdbcImportOptions#withFetchSize} to control driver-side streaming for large tables.
public final class JdbcImporter {

    private JdbcImporter() {
    }

    /// Imports all rows from {@code tableName} via {@code SELECT * FROM <tableName>} using default options.
    ///
    /// @param conn       open JDBC connection
    /// @param tableName  name of the table to export
    /// @param vortexPath destination path; created or truncated
    /// @throws SQLException if the query fails
    /// @throws IOException  if writing the Vortex file fails
    public static void importTable(Connection conn, String tableName, Path vortexPath) throws SQLException, IOException {
        importQuery(conn, "SELECT * FROM " + tableName, vortexPath);
    }

    /// Imports all rows returned by {@code sql} using default options.
    ///
    /// @param conn       open JDBC connection
    /// @param sql        SELECT statement to execute
    /// @param vortexPath destination path; created or truncated
    /// @throws SQLException if the query fails
    /// @throws IOException  if writing the Vortex file fails
    public static void importQuery(Connection conn, String sql, Path vortexPath) throws SQLException, IOException {
        importQuery(conn, sql, vortexPath, JdbcImportOptions.defaults());
    }

    /// Imports all rows returned by {@code sql} with the given options.
    ///
    /// @param conn       open JDBC connection
    /// @param sql        SELECT statement to execute
    /// @param vortexPath destination path; created or truncated
    /// @param options    fetch size, chunk size, write options, and optional progress listener
    /// @throws SQLException if the query fails
    /// @throws IOException  if writing the Vortex file fails
    public static void importQuery(Connection conn, String sql, Path vortexPath, JdbcImportOptions options)
            throws SQLException, IOException {
        try (PreparedStatement stmt = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            stmt.setFetchSize(options.fetchSize());
            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                DType.Struct schema = buildSchema(meta, colCount);
                try (FileChannel channel = FileChannel.open(vortexPath,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                     VortexWriter writer = VortexWriter.create(channel, schema, options.writeOptions())) {
                    importRows(rs, writer, schema, colCount, options);
                }
            }
        }
    }

    private static DType.Struct buildSchema(ResultSetMetaData meta, int colCount) throws SQLException {
        List<String> names = new ArrayList<>(colCount);
        List<DType> types = new ArrayList<>(colCount);
        for (int c = 1; c <= colCount; c++) {
            boolean nullable = meta.isNullable(c) != ResultSetMetaData.columnNoNulls;
            names.add(meta.getColumnLabel(c));
            types.add(SqlTypeToDType.map(meta.getColumnType(c), nullable));
        }
        return new DType.Struct(names, types, false);
    }

    private static void importRows(ResultSet rs, VortexWriter writer, DType.Struct schema,
                                   int colCount, JdbcImportOptions options) throws SQLException, IOException {
        int chunkSize = options.chunkSize();
        Object[] buffers = allocateBuffers(schema, colCount, chunkSize);
        int rowsInBuffer = 0;
        long totalRows = 0;

        while (rs.next()) {
            for (int c = 0; c < colCount; c++) {
                fillCell(buffers[c], rowsInBuffer, rs, c + 1, schema.fieldTypes().get(c));
            }
            rowsInBuffer++;
            if (rowsInBuffer == chunkSize) {
                writer.writeChunk(toChunkMap(schema, buffers, rowsInBuffer));
                totalRows += rowsInBuffer;
                rowsInBuffer = 0;
                buffers = allocateBuffers(schema, colCount, chunkSize);
                if (options.progressListener() != null) {
                    options.progressListener().onProgress(totalRows, -1);
                }
            }
        }

        if (rowsInBuffer > 0) {
            writer.writeChunk(toChunkMap(schema, buffers, rowsInBuffer));
        }
    }

    private static Object[] allocateBuffers(DType.Struct schema, int colCount, int size) {
        Object[] buffers = new Object[colCount];
        for (int c = 0; c < colCount; c++) {
            buffers[c] = allocateBuffer(schema.fieldTypes().get(c), size);
        }
        return buffers;
    }

    private static Object allocateBuffer(DType dtype, int size) {
        return switch (dtype) {
            case DType.Primitive p when p.ptype() == PType.I64 -> new long[size];
            case DType.Primitive p when p.ptype() == PType.I32 -> new int[size];
            case DType.Primitive p when p.ptype() == PType.I16 -> new short[size];
            case DType.Primitive p when p.ptype() == PType.I8 -> new byte[size];
            case DType.Primitive p when p.ptype() == PType.F64 -> new double[size];
            case DType.Primitive p when p.ptype() == PType.F32 -> new float[size];
            case DType.Bool ignored -> new boolean[size];
            case DType.Utf8 ignored -> new String[size];
            default -> throw new UnsupportedOperationException("unsupported dtype: " + dtype);
        };
    }

    private static void fillCell(Object buffer, int rowIdx, ResultSet rs, int colIdx, DType dtype)
            throws SQLException {
        if (dtype instanceof DType.Primitive p) {
            switch (p.ptype()) {
                case I64 -> ((long[]) buffer)[rowIdx] = rs.getLong(colIdx);
                case I32 -> ((int[]) buffer)[rowIdx] = rs.getInt(colIdx);
                case I16 -> ((short[]) buffer)[rowIdx] = rs.getShort(colIdx);
                case I8 -> ((byte[]) buffer)[rowIdx] = rs.getByte(colIdx);
                case F64 -> ((double[]) buffer)[rowIdx] = rs.getDouble(colIdx);
                case F32 -> ((float[]) buffer)[rowIdx] = rs.getFloat(colIdx);
                default -> throw new UnsupportedOperationException("unsupported primitive type: " + p.ptype());
            }
        } else if (dtype instanceof DType.Bool) {
            ((boolean[]) buffer)[rowIdx] = rs.getBoolean(colIdx);
        } else if (dtype instanceof DType.Utf8) {
            String val = rs.getString(colIdx);
            ((String[]) buffer)[rowIdx] = val != null ? val : "";
        } else {
            throw new UnsupportedOperationException("unsupported dtype: " + dtype);
        }
    }

    private static Map<String, Object> toChunkMap(DType.Struct schema, Object[] buffers, int rows) {
        List<String> names = schema.fieldNames();
        Map<String, Object> chunk = new LinkedHashMap<>();
        for (int c = 0; c < names.size(); c++) {
            chunk.put(names.get(c), trimBuffer(buffers[c], rows));
        }
        return chunk;
    }

    private static Object trimBuffer(Object buffer, int rows) {
        return switch (buffer) {
            case long[] arr -> rows == arr.length ? arr : Arrays.copyOf(arr, rows);
            case int[] arr -> rows == arr.length ? arr : Arrays.copyOf(arr, rows);
            case short[] arr -> rows == arr.length ? arr : Arrays.copyOf(arr, rows);
            case byte[] arr -> rows == arr.length ? arr : Arrays.copyOf(arr, rows);
            case double[] arr -> rows == arr.length ? arr : Arrays.copyOf(arr, rows);
            case float[] arr -> rows == arr.length ? arr : Arrays.copyOf(arr, rows);
            case boolean[] arr -> rows == arr.length ? arr : Arrays.copyOf(arr, rows);
            case String[] arr -> rows == arr.length ? arr : Arrays.copyOf(arr, rows);
            default -> throw new UnsupportedOperationException("unsupported buffer type: " + buffer.getClass());
        };
    }
}
