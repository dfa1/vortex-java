package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Lazy Sparse-encoded [Float16Array]. See [LazySparseLongArray] for semantics. The fill is kept
/// as its 16 raw bits, so a NaN payload or `-0.0` survives materialize.
///
/// @param dtype         logical element type
/// @param length        total logical row count
/// @param fillBits      raw half-precision bits at every unpatched position
/// @param patchValues   values for patched positions
/// @param patchIndices  sorted absolute positions of patches
/// @param offset        starting absolute position
public record LazySparseFloat16Array(
        DType dtype, long length, short fillBits,
        Float16Array patchValues, Array patchIndices, long offset)
        implements Float16Array {

    @Override
    public float getFloat(long i) {
        if (patchValues == null) {
            return Float.float16ToFloat(fillBits);
        }
        int p = SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
        return p >= 0 ? patchValues.getFloat(p) : Float.float16ToFloat(fillBits);
    }

    /// Zero-copy truncation: patches are found by absolute position, so only the row count
    /// shrinks.
    ///
    /// @param rows number of leading rows to keep
    /// @return a length-`rows` view over the same patches
    @Override
    public Array limited(long rows) {
        return rows >= length ? this : new LazySparseFloat16Array(dtype, rows, fillBits, patchValues, patchIndices, offset);
    }

    /// Materializes into a fresh little-endian half-precision segment: one walk over the rows in
    /// order, the fill for an unpatched row and the patch's own bits for a patched one. Patch
    /// values are copied through their pool's segment, not widened and narrowed.
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `f16` segment of `length()` elements
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        MemorySegment dst = arena.allocate(length * 2L, 2);
        long[] row = {0};
        if (patchValues == null) {
            SparseArrays.walkPatches(patchIndices, 0, offset, offset + length,
                    () -> dst.setAtIndex(VortexFormat.LE_SHORT, row[0]++, fillBits),
                    p -> { });
            return dst;
        }
        MemorySegment pool = patchValues.materialize(arena);
        SparseArrays.walkPatches(patchIndices, patchValues.length(), offset, offset + length,
                () -> dst.setAtIndex(VortexFormat.LE_SHORT, row[0]++, fillBits),
                p -> dst.setAtIndex(VortexFormat.LE_SHORT, row[0]++, pool.getAtIndex(VortexFormat.LE_SHORT, p)));
        return dst;
    }
}
