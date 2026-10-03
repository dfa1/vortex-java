package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloat16Array;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;

import java.lang.foreign.MemorySegment;

/// Wraps a decoded little-endian segment in the `MaterializedXxxArray` for its ptype. Callers
/// that only accept some ptypes (e.g. integer-only encodings) reject the rest before decoding.
final class MaterializedArrays {

    private MaterializedArrays() {
    }

    /// @param dtype the dtype the array reports
    /// @param ptype the physical element type of `seg`
    /// @param n     the element count
    /// @param seg   the decoded elements, little-endian
    /// @return a zero-copy array view over `seg`
    static Array of(DType dtype, PType ptype, long n, MemorySegment seg) {
        return switch (ptype) {
            case I8, U8 -> new MaterializedByteArray(dtype, n, seg);
            case I16, U16 -> new MaterializedShortArray(dtype, n, seg);
            case I32, U32 -> new MaterializedIntArray(dtype, n, seg);
            case I64, U64 -> new MaterializedLongArray(dtype, n, seg);
            case F16 -> new MaterializedFloat16Array(dtype, n, seg);
            case F32 -> new MaterializedFloatArray(dtype, n, seg);
            case F64 -> new MaterializedDoubleArray(dtype, n, seg);
        };
    }
}
