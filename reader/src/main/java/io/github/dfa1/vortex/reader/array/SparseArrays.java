package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.VortexException;

import java.util.function.IntConsumer;
import java.util.function.LongBinaryOperator;
import java.util.function.LongToIntFunction;

/// Package-private helpers shared by the `LazySparseXxxArray` records.
///
/// Centralises:
/// - the patch-indices array-type switch in [#readPatchIdx(Array, long)] so all six
///   records agree on supported patch-index Array types (U8/U16/U32/U64 backed by
///   [ByteArray]/[ShortArray]/[IntArray]/[LongArray]);
/// - the two binary-search variants used by scalar / sequential accessors:
///   [#findPatch(Array, long, long)] for exact hit-or-miss and
///   [#findFirstAtOrAfter(Array, long, long)] for the forEach run-walker; and
/// - [#walkPatches(Array, long, long, long, Runnable, IntConsumer)] for sequential
///   traversal shared by the typed forEach variants.
final class SparseArrays {

    private SparseArrays() {
    }

    /// Reads patch-index `k` from `idxArr` as an unsigned long.
    static long readPatchIdx(Array idxArr, long k) {
        return switch (idxArr) {
            case ByteArray ba -> Byte.toUnsignedLong(ba.getByte(k));
            case ShortArray sa -> Short.toUnsignedLong(sa.getShort(k));
            case IntArray ia -> Integer.toUnsignedLong(ia.getInt(k));
            case LongArray la -> la.getLong(k);
            default -> throw new VortexException(
                    "Sparse patch-indices: unsupported array type: " + idxArr.getClass().getSimpleName());
        };
    }

    /// Returns the index of the patch whose stored position equals `absPos`, or
    /// `-1` if no such patch exists. Binary searches over `[0, numPatches)`.
    static int findPatch(Array idxArr, long numPatches, long absPos) {
        int lo = 0;
        int hi = (int) numPatches - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = readPatchIdx(idxArr, mid);
            if (v == absPos) {
                return mid;
            }
            if (v < absPos) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }

    /// Returns the smallest index `k` such that `idxArr[k] >= absPos`, or
    /// `(int) numPatches` when no such `k` exists. Used by the forEach
    /// run-walker to locate the first patch that could fall inside the iteration range.
    static int findFirstAtOrAfter(Array idxArr, long numPatches, long absPos) {
        int lo = 0;
        int hi = (int) numPatches;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (readPatchIdx(idxArr, mid) < absPos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /// Walks all positions in `[absStart, absEnd)`, calling `fillSlot` for each fill
    /// position and `patchSlot` (with patch index `p`) for each patch in range.
    ///
    /// @param patchIndices sorted absolute patch positions
    /// @param numPatches   number of patches
    /// @param absStart     inclusive start of the output range
    /// @param absEnd       exclusive end of the output range
    /// @param fillSlot     invoked once per fill position
    /// @param patchSlot    invoked with patch index `p` for each in-range patch
    static void walkPatches(Array patchIndices, long numPatches, long absStart, long absEnd,
            Runnable fillSlot, IntConsumer patchSlot) {
        int p = findFirstAtOrAfter(patchIndices, numPatches, absStart);
        long pos = absStart;
        while (pos < absEnd && p < numPatches) {
            long patchAbs = readPatchIdx(patchIndices, p);
            if (patchAbs >= absEnd) {
                break;
            }
            for (long r = pos; r < patchAbs; r++) {
                fillSlot.run();
            }
            patchSlot.accept(p);
            pos = patchAbs + 1;
            p++;
        }
        for (long r = pos; r < absEnd; r++) {
            fillSlot.run();
        }
    }

    /// Resolves the int value at absolute position `absPos`: the patch value when a
    /// patch lands there, otherwise `fillInt`. Shared by the byte/short/int sparse
    /// records whose `getInt` differs only in the patch-value accessor.
    ///
    /// @param patchIndices sorted absolute patch positions (may be `null` when `numPatches == 0`)
    /// @param numPatches   number of patches; `0` for a no-patch array
    /// @param absPos       absolute position to resolve
    /// @param patchGetInt  reads patch value `p` as an int
    /// @param fillInt      value at unpatched positions
    /// @return the resolved int value
    static int patchedInt(Array patchIndices, long numPatches, long absPos,
            LongToIntFunction patchGetInt, int fillInt) {
        int p = findPatch(patchIndices, numPatches, absPos);
        return p >= 0 ? patchGetInt.applyAsInt(p) : fillInt;
    }

    /// Folds the int view of a sparse range, emitting `fillInt` for unpatched
    /// positions and the patch value for patched ones. Shared by the byte/short/int
    /// sparse records.
    ///
    /// @param patchIndices sorted absolute patch positions (may be `null` when `numPatches == 0`)
    /// @param numPatches   number of patches; `0` for a no-patch array
    /// @param offset       starting absolute position
    /// @param length       logical row count
    /// @param patchGetInt  reads patch value `p` as an int
    /// @param fillInt      value at unpatched positions
    /// @param identity     initial accumulator
    /// @param op           reduction operator
    /// @return the accumulated result
    static long foldInt(Array patchIndices, long numPatches, long offset, long length,
            LongToIntFunction patchGetInt, int fillInt, long identity, LongBinaryOperator op) {
        long[] acc = {identity};
        walkPatches(patchIndices, numPatches, offset, offset + length,
                () -> acc[0] = op.applyAsLong(acc[0], fillInt),
                p -> acc[0] = op.applyAsLong(acc[0], patchGetInt.applyAsInt(p)));
        return acc[0];
    }
}
