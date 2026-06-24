package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

/// Lazy Sparse-encoded [BoolArray]. See [LazySparseLongArray] for semantics.
///
/// @param dtype         logical Bool type
/// @param length        total logical row count
/// @param fillValue     value at every unpatched position (typically `false`)
/// @param patchValues   boolean values for patched positions
/// @param patchIndices  sorted absolute positions of patches
/// @param offset        starting absolute position
public record LazySparseBoolArray(
        DType dtype, long length, boolean fillValue,
        BoolArray patchValues, Array patchIndices, long offset)
        implements BoolArray {

    @Override
    public boolean getBoolean(long i) {
        int p = SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
        return p >= 0 ? patchValues.getBoolean(p) : fillValue;
    }

    @Override
    public void forEachBoolean(BooleanConsumer c) {
        long numPatches = patchValues.length();
        SparseArrays.walkPatches(patchIndices, numPatches, offset, offset + length,
                () -> c.accept(fillValue),
                p -> c.accept(patchValues.getBoolean(p)));
    }
}
