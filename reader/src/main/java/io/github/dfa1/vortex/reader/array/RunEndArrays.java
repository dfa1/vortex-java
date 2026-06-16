package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.VortexException;

/// Package-private helpers shared by the `LazyRunEndXxxArray` records.
///
/// Centralises:
/// - the run-ends array-type switch in [#readRunEnd(Array, long)] so all four records
///   agree on supported run-ends Array types (U8/U16/U32/U64 backed by
///   [ByteArray]/[ShortArray]/[IntArray]/[LongArray]); and
/// - the binary search in [#findRun(Array, long, long)] used by every scalar
///   accessor.
final class RunEndArrays {

    private RunEndArrays() {
    }

    /// Reads run-end `k` from `endsArr` as an unsigned long. One switch on the
    /// run-time concrete type per call — JIT inline-caches on the stable type.
    static long readRunEnd(Array endsArr, long k) {
        return switch (endsArr) {
            case ByteArray ba -> Byte.toUnsignedLong(ba.getByte(k));
            case ShortArray sa -> Short.toUnsignedLong(sa.getShort(k));
            case IntArray ia -> Integer.toUnsignedLong(ia.getInt(k));
            case LongArray la -> la.getLong(k);
            default -> throw new VortexException(
                    "RunEnd run-ends: unsupported array type: " + endsArr.getClass().getSimpleName());
        };
    }

    /// Locates the run that covers absolute position `absPos`: the smallest
    /// `k` such that `runEnds[k] > absPos`. Binary searches over the
    /// `numRuns` entries of `endsArr`.
    static int findRun(Array endsArr, long numRuns, long absPos) {
        int lo = 0;
        int hi = (int) (numRuns - 1);
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (readRunEnd(endsArr, mid) > absPos) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }
}
