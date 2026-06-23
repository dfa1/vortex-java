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
        long numPatches = patchValues == null ? 0 : patchValues.length();
        SparseArrays.walkPatches(patchIndices, numPatches, offset, offset + length,
                () -> acc[0] = op.applyAsDouble(acc[0], fillValue),
                p -> acc[0] = op.applyAsDouble(acc[0], patchValues.getFloat(p)));
        return acc[0];
    }
}
