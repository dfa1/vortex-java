package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

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
            c.accept(patchValues.getBoolean(p));
            pos = patchAbs + 1;
            p++;
        }
        for (long r = pos; r < absEnd; r++) {
            c.accept(fillValue);
        }
    }
}
