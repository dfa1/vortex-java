package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;

/// Lazy [DoubleArray] backed by the {@code vortex.alp} encoded {@code i64} child segment.
///
/// Decode is deferred to element access: {@code getDouble(i) = (double) encoded[i] * scale}.
/// Returned by {@link io.github.dfa1.vortex.reader.decode.AlpEncodingDecoder} when the chunk has
/// no patches and the source is not a broadcast constant; patched or broadcast chunks fall back
/// to [MaterializedDoubleArray].
///
/// The {@code arena} component is the chunk-scoped allocator used when a caller forces
/// materialisation through {@link ArraySegments#of(Array)} — its lifetime matches the encoded
/// segment, so escaping the chunk's try-with-resources frees both.
///
/// @param dtype   logical F64 type
/// @param length  number of logical elements
/// @param encoded backing {@code i64} segment (one long per row)
/// @param scale   combined ALP scale ({@code 10^exp_f * 10^(-exp_e)})
public record LazyAlpDoubleArray(DType dtype, long length, MemorySegment encoded, double scale)
        implements DoubleArray {

    @Override
    public double getDouble(long i) {
        return (double) encoded.getAtIndex(PTypeIO.LE_LONG, i) * scale;
    }
}
