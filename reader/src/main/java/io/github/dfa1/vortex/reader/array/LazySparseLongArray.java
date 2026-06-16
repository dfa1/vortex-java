package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Lazy Sparse-encoded [LongArray]: {@code getLong(i) = patches[binSearch(i + offset)]
/// or fillValue}. The `patchIndices` array is typed as [Array] because the
/// indices ptype varies — backed by one of [ByteArray], [ShortArray],
/// [IntArray], [LongArray].
///
/// `forEachLong` / `fold` walk the patches in order, emitting `fillValue`
/// for runs of unpatched positions — O(numPatches) binary-search-equivalent steps plus
/// `length` emissions, not O(length × log(numPatches)).
///
/// @param dtype         logical element type
/// @param length        total logical row count
/// @param fillValue     value at every unpatched position
/// @param patchValues   values for patched positions; length = `numPatches`
/// @param patchIndices  sorted absolute positions of patches; length = `numPatches`
/// @param offset        starting absolute position; logical row `i` maps to
///                      absolute `i + offset`
public record LazySparseLongArray(
        DType dtype, long length, long fillValue,
        LongArray patchValues, Array patchIndices, long offset)
        implements LongArray {

    @Override
    public long getLong(long i) {
        if (patchValues == null) {
            return fillValue;
        }
        int p = SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
        return p >= 0 ? patchValues.getLong(p) : fillValue;
    }

    @Override
    public void forEachLong(LongConsumer c) {
        if (patchValues == null) {
            for (long r = 0; r < length; r++) {
                c.accept(fillValue);
            }
            return;
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
                c.accept(fillValue);
            }
            c.accept(patchValues.getLong(p));
            pos = patchAbs + 1;
            p++;
        }
        for (long r = pos; r < absEnd; r++) {
            c.accept(fillValue);
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long[] acc = {identity};
        forEachLong(v -> acc[0] = op.applyAsLong(acc[0], v));
        return acc[0];
    }
}
