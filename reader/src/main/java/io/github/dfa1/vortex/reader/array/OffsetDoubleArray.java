package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Optional;
import java.util.function.DoubleBinaryOperator;

/// Sliced view over a [DoubleArray]: `getDouble(i) = inner.getDouble(i + offset)`.
///
/// @param dtype  logical element type
/// @param length number of logical elements in this slice
/// @param inner  underlying double array
/// @param offset starting index into `inner`
public record OffsetDoubleArray(DType dtype, long length, DoubleArray inner, long offset)
        implements DoubleArray {

    @Override
    public double getDouble(long i) {
        return inner.getDouble(i + offset);
    }

    /// Folds the window straight off a flat inner buffer (see `OffsetArrays#window`): a scan window
    /// of a shared flat lands here, and the per-row default paid two virtual `getDouble` calls a row.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to the accumulator and each double element
    /// @return the final accumulated result
    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        Optional<MemorySegment> window = OffsetArrays.window(inner, offset, length, 8);
        if (window.isEmpty()) {
            return DoubleArray.super.fold(identity, op);
        }
        MemorySegment seg = window.get();
        double result = identity;
        for (long i = 0; i < length; i++) {
            result = op.applyAsDouble(result, seg.getAtIndex(VortexFormat.LE_DOUBLE, i));
        }
        return result;
    }

    /// Zero-copy over a flat inner buffer (see `OffsetArrays#window`), else the per-row default.
    ///
    /// @param arena allocator for the per-row fallback
    /// @return the window's little-endian values
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        return OffsetArrays.window(inner, offset, length, 8).orElseGet(() -> DoubleArray.super.materialize(arena));
    }
}
