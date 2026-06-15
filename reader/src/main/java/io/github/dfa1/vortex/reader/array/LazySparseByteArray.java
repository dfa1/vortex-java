package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.LongBinaryOperator;

/// Lazy Sparse-encoded {@link ByteArray}. See {@link LazySparseLongArray} for semantics.
///
/// @param dtype         logical element type
/// @param length        total logical row count
/// @param fillValue     value at every unpatched position (raw signed byte)
/// @param fillInt       value at every unpatched position widened to int (unsigned-aware for U8)
/// @param patchValues   values for patched positions
/// @param patchIndices  sorted absolute positions of patches
/// @param offset        starting absolute position
public record LazySparseByteArray(
        DType dtype, long length, byte fillValue, int fillInt,
        ByteArray patchValues, Array patchIndices, long offset)
        implements ByteArray {

    @Override
    public byte getByte(long i) {
        if (patchValues == null) {
            return fillValue;
        }
        int p = SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
        return p >= 0 ? patchValues.getByte(p) : fillValue;
    }

    @Override
    public int getInt(long i) {
        if (patchValues == null) {
            return fillInt;
        }
        int p = SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
        return p >= 0 ? patchValues.getInt(p) : fillInt;
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long[] acc = {identity};
        if (patchValues == null) {
            for (long r = 0; r < length; r++) {
                acc[0] = op.applyAsLong(acc[0], fillInt);
            }
            return acc[0];
        }
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
