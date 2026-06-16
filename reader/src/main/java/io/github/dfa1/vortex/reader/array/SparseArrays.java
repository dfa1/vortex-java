package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.VortexException;

/// Package-private helpers shared by the `LazySparseXxxArray` records.
///
/// Centralises:
/// - the patch-indices array-type switch in [#readPatchIdx(Array, long)] so all six
///   records agree on supported patch-index Array types (U8/U16/U32/U64 backed by
///   [ByteArray]/[ShortArray]/[IntArray]/[LongArray]); and
/// - the two binary-search variants used by scalar / sequential accessors:
///   [#findPatch(Array, long, long)] for exact hit-or-miss and
///   [#findFirstAtOrAfter(Array, long, long)] for the forEach run-walker.
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
}
