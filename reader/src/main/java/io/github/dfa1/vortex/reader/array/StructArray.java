package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.List;

/// Decoded struct array: holds one [Array] per field, keyed by position.
///
/// Returned by [io.github.dfa1.vortex.encoding.StructEncoding] when decoding a
/// multi-field `vortex.struct` segment (all columns packed into one flat).
public final class StructArray implements Array {

    private final DType.Struct dtype;
    private final long length;
    private final List<Array> fields;

    /// Creates a new `StructArray` from the given field arrays.
    ///
    /// @param dtype  struct dtype providing field names and types
    /// @param length number of rows (all field arrays must have the same length)
    /// @param fields per-field arrays in the same order as `dtype.fieldTypes()`
    public StructArray(DType.Struct dtype, long length, List<Array> fields) {
        this.dtype = dtype;
        this.length = length;
        this.fields = List.copyOf(fields);
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    /// Returns the number of fields in this struct.
    ///
    /// @return the field count
    public int fieldCount() {
        return fields.size();
    }

    /// Returns the field array at the given positional index.
    ///
    /// @param i zero-based field index
    /// @return the field array at position `i`
    public Array field(int i) {
        return fields.get(i);
    }

    /// Returns the field array with the given name.
    ///
    /// @param name the field name to look up
    /// @return the field array with the given name
    public Array field(String name) {
        List<String> names = dtype.fieldNames();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(name)) {
                return fields.get(i);
            }
        }
        throw new VortexException("no field '" + name + "' in struct");
    }

    @Override
    public Array limited(long rows) {
        return new StructArray(dtype, rows, fields.stream().map(f -> Array.limited(f, rows)).toList());
    }

    /// Always throws: a struct has one segment per field, not a single primary
    /// segment. Materialise each [#field(int)] separately.
    ///
    /// @param arena unused
    /// @return never returns
    /// @throws VortexException always
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        throw new VortexException("StructArray has no primary segment");
    }
}
