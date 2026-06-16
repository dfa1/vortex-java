package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.DoubleBinaryOperator;

/// Lazy Sparse-encoded [FloatArray]. See [LazySparseLongArray] for semantics.
///
/// @param dtype         logical element type
/// @param length        total logical row count
/// @param fillValue     value at every unpatched position
/// @param patchValues   values for patched positions
/// @param patchIndices  sorted absolute positions of patches
/// @param offset        starting absolute position
public record LazySparseFloatArray(
        DType dtype, long length, float fillValue,
        FloatArray patchValues, Array patchIndices, long offset)
        implements FloatArray {

    @Override
    public float getFloat(long i) {
        if (patchValues == null) {
            return fillValue;
        }
        int p = SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
        return p >= 0 ? patchValues.getFloat(p) : fillValue;
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        double[] acc = {identity};
        if (patchValues == null) {
            for (long r = 0; r < length; r++) {
                acc[0] = op.applyAsDouble(acc[0], fillValue);
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
                acc[0] = op.applyAsDouble(acc[0], fillValue);
            }
            acc[0] = op.applyAsDouble(acc[0], patchValues.getFloat(p));
            pos = patchAbs + 1;
            p++;
        }
        for (long r = pos; r < absEnd; r++) {
            acc[0] = op.applyAsDouble(acc[0], fillValue);
        }
        return acc[0];
    }
}
