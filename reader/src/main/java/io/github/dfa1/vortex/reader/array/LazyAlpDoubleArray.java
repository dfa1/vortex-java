package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;

/// Lazy [DoubleArray] backed by the `vortex.alp` encoded `i64` child segment.
///
/// Decode is deferred to element access:
/// `getDouble(i) = (double) encoded[i] * factorF * factorE`. Two-step multiplication
/// mirrors the Rust reference (`ALPFloat::decode_single`) — pre-multiplying the two factors
/// into a single `scale` gives different IEEE rounding for non-trivial `expF`,
/// breaking round-trip with the encoder's verify step.
/// Returned by {@link io.github.dfa1.vortex.reader.decode.AlpEncodingDecoder} when the chunk has
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
}
