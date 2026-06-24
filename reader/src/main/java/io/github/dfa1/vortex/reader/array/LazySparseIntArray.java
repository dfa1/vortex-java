package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// Lazy Sparse-encoded [IntArray]. See [LazySparseLongArray] for semantics.
///
/// @param dtype         logical element type
/// @param length        total logical row count
/// @param fillValue     value at every unpatched position
/// @param patchValues   values for patched positions
/// @param patchIndices  sorted absolute positions of patches
/// @param offset        starting absolute position
public record LazySparseIntArray(
        DType dtype, long length, int fillValue,
        IntArray patchValues, Array patchIndices, long offset)
        implements IntArray {

    @Override
    public int getInt(long i) {
        if (patchValues == null) {
            return fillValue;
        }
        int p = SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
        return p >= 0 ? patchValues.getInt(p) : fillValue;
    }

    @Override
    public void forEachInt(IntConsumer c) {
        long numPatches = patchValues == null ? 0 : patchValues.length();
        SparseArrays.walkPatches(patchIndices, numPatches, offset, offset + length,
                () -> c.accept(fillValue),
                p -> c.accept(patchValues.getInt(p)));
    }

    @Override
    public int fold(int identity, IntBinaryOperator op) {
        int[] acc = {identity};
        forEachInt(v -> acc[0] = op.applyAsInt(acc[0], v));
        return acc[0];
    }
}
