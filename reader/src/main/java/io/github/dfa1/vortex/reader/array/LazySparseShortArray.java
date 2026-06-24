package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.function.LongBinaryOperator;

/// Lazy Sparse-encoded [ShortArray]. See [LazySparseLongArray] for semantics.
///
/// @param dtype         logical element type
/// @param length        total logical row count
/// @param fillValue     value at every unpatched position (raw signed short)
/// @param fillInt       value at every unpatched position widened to int (unsigned-aware for U16)
/// @param patchValues   values for patched positions
/// @param patchIndices  sorted absolute positions of patches
/// @param offset        starting absolute position
public record LazySparseShortArray(
        DType dtype, long length, short fillValue, int fillInt,
        ShortArray patchValues, Array patchIndices, long offset)
        implements ShortArray {

    @Override
    public short getShort(long i) {
        if (patchValues == null) {
            return fillValue;
        }
        int p = SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
        return p >= 0 ? patchValues.getShort(p) : fillValue;
    }

    @Override
    public int getInt(long i) {
        long numPatches = patchValues == null ? 0 : patchValues.length();
        return SparseArrays.patchedInt(patchIndices, numPatches, i + offset, this::patchInt, fillInt);
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long numPatches = patchValues == null ? 0 : patchValues.length();
        return SparseArrays.foldInt(patchIndices, numPatches, offset, length,
                this::patchInt, fillInt, identity, op);
    }

    private int patchInt(long p) {
        return patchValues.getInt(p);
    }
}
