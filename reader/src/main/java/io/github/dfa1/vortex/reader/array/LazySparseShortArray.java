package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.LongBinaryOperator;

/// Lazy Sparse-encoded {@link ShortArray}. See {@link LazySparseLongArray} for semantics.
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
        int p = SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
        return p >= 0 ? patchValues.getShort(p) : fillValue;
    }

    @Override
    public int getInt(long i) {
        int p = SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
        return p >= 0 ? patchValues.getInt(p) : fillInt;
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long[] acc = {identity};
        long numPatches = patchValues.length();
        long absStart = offset;
        long absEnd = offset + length;
        int p = SparseArrays.findFirstAtOrAfter(patchIndices, numPatches, absStart);
        long pos = absStart;
        while (pos < absEnd && p < numPatches) {
            long patchAbs = SparseArrays.readPatchIdx(patchIndices, p);
            if (patchAbs >= absEnd) {
                break;
            }
            for (long r = pos; r < patchAbs; r++) {
                acc[0] = op.applyAsLong(acc[0], fillInt);
            }
            acc[0] = op.applyAsLong(acc[0], patchValues.getInt(p));
            pos = patchAbs + 1;
            p++;
        }
        for (long r = pos; r < absEnd; r++) {
            acc[0] = op.applyAsLong(acc[0], fillInt);
        }
        return acc[0];
    }
}
