package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Lazy [FloatArray] backed by the `vortex.alp` encoded `i32` child segment.
///
/// Decode is deferred to element access:
/// `getFloat(i) = (float) encoded[i] * factorF * factorE`. Two-step multiplication mirrors
/// the Rust reference; see [LazyAlpDoubleArray] for rationale.
///
/// @param dtype   logical F32 type
/// @param length  number of logical elements
/// @param encoded backing `i32` segment (one int per row)
/// @param factorF `10^exp_f`
/// @param factorE `10^(-exp_e)`
public record LazyAlpFloatArray(DType dtype, long length, MemorySegment encoded,
                                float factorF, float factorE)
        implements FloatArray {

    @Override
    public float getFloat(long i) {
        return encoded.getAtIndex(PTypeIO.LE_INT, i) * factorF * factorE;
    }

    /// Bulk-decodes through [#getFloat(long)] into a fresh little-endian `f32` segment.
    /// The decode formula (including the two-step factor application that preserves IEEE
    /// rounding) lives only in [#getFloat(long)]; this override exists solely to give the
    /// JIT a monomorphic, inlinable call site (the shared [FloatArray] default is
    /// megamorphic across every implementation and will not inline or auto-vectorize).
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `f32` segment of decoded values
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n * 4L, 4);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_FLOAT, i, getFloat(i));
        }
        return dst;
    }
}
