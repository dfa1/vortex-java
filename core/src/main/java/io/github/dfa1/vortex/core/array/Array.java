package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;

/// Decoded columnar data. Concrete subtypes specialise element access for the JIT;
/// each covers a specific dtype family.
///
/// Buffers are `MemorySegment` slices backed by the memory-mapped file; lifetime
/// is tied to the `VortexFile`'s Arena.
public sealed interface Array
        permits BoolArray, ByteArray, DoubleArray, EmptyArray, FixedSizeListArray, Float16Array,
                        FloatArray, GenericArray, IntArray, ListArray, ListViewArray, LongArray,
                        MaskedArray, NullArray, ShortArray, StructArray, UnknownArray, VarBinArray {

    /// Returns the number of elements in this array.
    ///
    /// @return element count
    long length();

    /// Returns the logical type of elements in this array.
    ///
    /// @return dtype
    DType dtype();

    /// Returns the primary backing segment for this array (e.g., the raw little-endian values for primitive arrays).
    ///
    /// <p>Supported by all primitive array types and {@link VarBinArray} (returns the bytes segment).
    /// Throws for array types with no single primary buffer (e.g., {@link StructArray}, {@link MaskedArray}).
    ///
    /// @return the primary backing {@link MemorySegment}
    /// @throws VortexException if this array type has no single primary segment
    default MemorySegment segment() {
        throw new VortexException(getClass().getSimpleName() + " has no primary segment");
    }

}
