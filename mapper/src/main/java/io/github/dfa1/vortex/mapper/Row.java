package io.github.dfa1.vortex.mapper;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import java.util.List;

/// One row of a scan, handed to the mapper of [Rows#stream]. Columns are addressed by their position
/// in the projection: `getDouble(0)` reads the first column listed in
/// [io.github.dfa1.vortex.reader.ScanOptions#columns(ColumnName...)].
///
/// Each getter reads one Java type and fails with [IllegalArgumentException] on a column of another
/// type: `getInt` reads `I32` and `U32`, `getLong` reads `I64` and `U64`, and so on. Unsigned values
/// come back as the raw bits of the same-width type; widen them with [Integer#toUnsignedLong(int)]
/// and friends. `getString` reads `Utf8`; `getBytes` reads `Binary` and `Utf8`.
///
/// For a null cell, `getString` and `getBytes` return `null`; the primitive getters return an
/// unspecified value, so check [#isNull(int)] first on a nullable column.
///
/// The same `Row` instance moves along the scan: read what you need inside the mapper and do not
/// keep it. Strings and byte arrays are copies, so values taken out of it stay valid after the scan.
public final class Row {

    private final ColumnName[] names;
    private final DType[] dtypes;
    private final Array[] values;
    private final BoolArray[] validity;
    private long index;

    Row(Chunk chunk, List<ColumnName> columns) {
        int n = columns.size();
        names = new ColumnName[n];
        dtypes = new DType[n];
        values = new Array[n];
        validity = new BoolArray[n];
        for (int i = 0; i < n; i++) {
            Chunk.Column column = chunk.columns().get(columns.get(i));
            MaskedArray.Unwrapped unwrapped = MaskedArray.unwrap(column.array());
            names[i] = columns.get(i);
            dtypes[i] = column.dtype();
            values[i] = unwrapped.inner();
            validity[i] = unwrapped.validity();
        }
    }

    void moveTo(long row) {
        index = row;
    }

    /// Whether the cell is null.
    ///
    /// @param column the column's position in the projection
    /// @return `true` if the cell holds no value
    public boolean isNull(int column) {
        // a column that is null in every row of the chunk decodes to a NullArray payload
        return values[column] instanceof NullArray
                || validity[column] != null && !validity[column].getBoolean(index);
    }

    /// Reads a `Bool` cell.
    ///
    /// @param column the column's position in the projection
    /// @return the value
    public boolean getBoolean(int column) {
        if (values[column] instanceof BoolArray a) {
            return a.getBoolean(index);
        }
        requireNullColumn(column, "getBoolean");
        return false;
    }

    /// Reads an `I8` or `U8` cell; a `U8` comes back as its raw bits.
    ///
    /// @param column the column's position in the projection
    /// @return the value
    public byte getByte(int column) {
        if (values[column] instanceof ByteArray a) {
            return a.getByte(index);
        }
        requireNullColumn(column, "getByte");
        return 0;
    }

    /// Reads an `I16` or `U16` cell; a `U16` comes back as its raw bits.
    ///
    /// @param column the column's position in the projection
    /// @return the value
    public short getShort(int column) {
        if (values[column] instanceof ShortArray a) {
            return a.getShort(index);
        }
        requireNullColumn(column, "getShort");
        return 0;
    }

    /// Reads an `I32` or `U32` cell; a `U32` comes back as its raw bits.
    ///
    /// @param column the column's position in the projection
    /// @return the value
    public int getInt(int column) {
        if (values[column] instanceof IntArray a) {
            return a.getInt(index);
        }
        requireNullColumn(column, "getInt");
        return 0;
    }

    /// Reads an `I64` or `U64` cell; a `U64` comes back as its raw bits.
    ///
    /// @param column the column's position in the projection
    /// @return the value
    public long getLong(int column) {
        if (values[column] instanceof LongArray a) {
            return a.getLong(index);
        }
        requireNullColumn(column, "getLong");
        return 0L;
    }

    /// Reads an `F32` cell.
    ///
    /// @param column the column's position in the projection
    /// @return the value
    public float getFloat(int column) {
        if (values[column] instanceof FloatArray a) {
            return a.getFloat(index);
        }
        requireNullColumn(column, "getFloat");
        return 0f;
    }

    /// Reads an `F64` cell.
    ///
    /// @param column the column's position in the projection
    /// @return the value
    public double getDouble(int column) {
        if (values[column] instanceof DoubleArray a) {
            return a.getDouble(index);
        }
        requireNullColumn(column, "getDouble");
        return 0d;
    }

    /// Reads a `Utf8` cell.
    ///
    /// @param column the column's position in the projection
    /// @return the value, or `null` for a null cell
    public String getString(int column) {
        if (!(dtypes[column] instanceof DType.Utf8)) {
            throw mismatch(column, "getString");
        }
        return isNull(column) ? null : ((VarBinArray) values[column]).getString(index);
    }

    /// Reads a `Binary` or `Utf8` cell as bytes.
    ///
    /// @param column the column's position in the projection
    /// @return a copy of the value, or `null` for a null cell
    public byte[] getBytes(int column) {
        if (!(dtypes[column] instanceof DType.Binary || dtypes[column] instanceof DType.Utf8)) {
            throw mismatch(column, "getBytes");
        }
        return isNull(column) ? null : ((VarBinArray) values[column]).getBytes(index);
    }

    // An all-null column has no typed payload to read from; any primitive getter is fine on it.
    private void requireNullColumn(int column, String getter) {
        if (!(values[column] instanceof NullArray)) {
            throw mismatch(column, getter);
        }
    }

    private IllegalArgumentException mismatch(int column, String getter) {
        return new IllegalArgumentException("column " + column + " '" + names[column] + "' is " + dtypes[column]
                + ", which " + getter + " cannot read");
    }
}
