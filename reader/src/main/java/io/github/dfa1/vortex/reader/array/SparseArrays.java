package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.VortexException;

import java.util.function.IntConsumer;

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
}
