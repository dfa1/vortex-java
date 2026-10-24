package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Optional;
import java.util.function.IntBinaryOperator;

/// Sliced view over an [IntArray]: `getInt(i) = inner.getInt(i + offset)`.
///
/// @param dtype  logical element type
/// @param length number of logical elements in this slice
/// @param inner  underlying int array
/// @param offset starting index into `inner`
public record OffsetIntArray(DType dtype, long length, IntArray inner, long offset)
        implements IntArray {

    @Override
    public int getInt(long i) {
        return inner.getInt(i + offset);
    }

    /// Folds the window straight off a flat inner buffer (see `OffsetArrays#window`): a scan window
    /// of a shared flat lands here, and the per-row default paid two virtual `getInt` calls a row.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to the accumulator and each int element
    /// @return the final accumulated result
    @Override
    public int fold(int identity, IntBinaryOperator op) {
        Optional<MemorySegment> window = OffsetArrays.window(inner, offset, length, 4);
        if (window.isEmpty()) {
            return IntArray.super.fold(identity, op);
        }
        MemorySegment seg = window.get();
        int result = identity;
        for (long i = 0; i < length; i++) {
            result = op.applyAsInt(result, seg.getAtIndex(VortexFormat.LE_INT, i));
        }
        return result;
    }

    /// Zero-copy over a flat inner buffer (see `OffsetArrays#window`), else the per-row default.
    ///
    /// @param arena allocator for the per-row fallback
    /// @return the window's little-endian values
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        return OffsetArrays.window(inner, offset, length, 4).orElseGet(() -> IntArray.super.materialize(arena));
    }
}
