package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

/// Decoded columnar data. Concrete subtypes specialise element access for the JIT;
/// each covers a specific dtype family.
///
/// Buffers are `MemorySegment` slices backed by the memory-mapped file; lifetime
/// is tied to the `VortexFile`'s Arena.
public sealed interface Array
        permits BoolArray, ByteArray, DoubleArray, EmptyArray, FixedSizeListArray, Float16Array,
                        FloatArray, GenericArray, IntArray, LazyDecimalArray, LazyDecimalBytePartsArray,
                        ListArray, ListViewArray, LongArray, MaskedArray, NullArray, ShortArray,
                        StructArray, UnknownArray, VarBinArray, VariantArray {

    /// Returns the number of elements in this array.
    ///
    /// @return element count
    long length();

    /// Returns the logical type of elements in this array.
    ///
    /// @return dtype
    DType dtype();


}
