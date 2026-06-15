package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Lazy Sparse-encoded {@link DoubleArray}. See {@link LazySparseLongArray} for semantics.
///
/// @param dtype         logical element type
/// @param length        total logical row count
/// @param fillValue     value at every unpatched position
/// @param patchValues   values for patched positions
/// @param patchIndices  sorted absolute positions of patches
/// @param offset        starting absolute position
public record LazySparseDoubleArray(
        DType dtype, long length, double fillValue,
        DoubleArray patchValues, Array patchIndices, long offset)
        implements DoubleArray {

    @Override
    public double getDouble(long i) {
        int p = SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
        return p >= 0 ? patchValues.getDouble(p) : fillValue;
    }

    @Override
    public void forEachDouble(DoubleConsumer c) {
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
                c.accept(fillValue);
            }
            c.accept(patchValues.getDouble(p));
            pos = patchAbs + 1;
            p++;
        }
        for (long r = pos; r < absEnd; r++) {
            c.accept(fillValue);
        }
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        double[] acc = {identity};
        forEachDouble(v -> acc[0] = op.applyAsDouble(acc[0], v));
        return acc[0];
    }
}
