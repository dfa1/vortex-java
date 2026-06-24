package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Lazy Sparse-encoded [DoubleArray]. See [LazySparseLongArray] for semantics.
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
        if (patchValues == null) {
            return fillValue;
        }
        int p = SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
        return p >= 0 ? patchValues.getDouble(p) : fillValue;
    }

    @Override
    public void forEachDouble(DoubleConsumer c) {
        long numPatches = patchValues == null ? 0 : patchValues.length();
        SparseArrays.walkPatches(patchIndices, numPatches, offset, offset + length,
                () -> c.accept(fillValue),
                p -> c.accept(patchValues.getDouble(p)));
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        double[] acc = {identity};
        forEachDouble(v -> acc[0] = op.applyAsDouble(acc[0], v));
        return acc[0];
    }
}
