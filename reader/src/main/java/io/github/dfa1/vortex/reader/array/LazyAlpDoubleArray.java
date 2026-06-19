package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Lazy [DoubleArray] backed by the `vortex.alp` encoded `i64` child segment.
///
/// Decode is deferred to element access:
/// `getDouble(i) = (double) encoded[i] * factorF * factorE`. Two-step multiplication
/// mirrors the Rust reference (`ALPFloat::decode_single`) — pre-multiplying the two factors
/// into a single `scale` gives different IEEE rounding for non-trivial `expF`,
/// breaking round-trip with the encoder's verify step.
/// Returned by [io.github.dfa1.vortex.reader.decode.AlpEncodingDecoder] when the chunk has
/// no patches and the source is not a broadcast constant; patched or broadcast chunks fall back
/// to [MaterializedDoubleArray].
///
/// @param dtype   logical F64 type
/// @param length  number of logical elements
/// @param encoded backing `i64` segment (one long per row)
/// @param factorF `10^exp_f`
/// @param factorE `10^(-exp_e)`
public record LazyAlpDoubleArray(DType dtype, long length, MemorySegment encoded,
                                 double factorF, double factorE)
        implements DoubleArray {

    @Override
    public double getDouble(long i) {
        return (double) encoded.getAtIndex(PTypeIO.LE_LONG, i) * factorF * factorE;
    }

    /// Bulk-decodes through [#getDouble(long)] into a fresh little-endian `f64` segment.
    /// The decode formula (including the two-step factor application that preserves IEEE
    /// rounding) lives only in [#getDouble(long)]; this override exists solely to give the
    /// JIT a monomorphic, inlinable call site (the shared [DoubleArray] default is
    /// megamorphic across every implementation and will not inline or auto-vectorise).
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `f64` segment of decoded values
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n * 8L, 8);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(PTypeIO.LE_DOUBLE, i, getDouble(i));
        }
        return dst;
    }
}
