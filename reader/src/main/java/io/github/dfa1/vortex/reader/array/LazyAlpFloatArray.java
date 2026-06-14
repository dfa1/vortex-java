package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.DoubleBinaryOperator;

/// Lazy [FloatArray] backed by the {@code vortex.alp} encoded {@code i32} child segment.
///
/// Decode is deferred to element access: {@code getFloat(i) = (float) encoded[i] * scale}.
/// Returned by {@link io.github.dfa1.vortex.reader.decode.AlpEncodingDecoder} when the F32 chunk
/// has no patches and the source is not a broadcast constant.
///
/// @param dtype   logical F32 type
/// @param length  number of logical elements
/// @param encoded backing {@code i32} segment (one int per row)
/// @param scale   combined ALP scale ({@code 10^exp_f * 10^(-exp_e)})
/// @param arena   chunk-scoped allocator used for on-demand materialisation
public record LazyAlpFloatArray(DType dtype, long length, MemorySegment encoded, float scale,
                                SegmentAllocator arena)
        implements FloatArray {

    @Override
    public float getFloat(long i) {
        return (float) encoded.getAtIndex(PTypeIO.LE_INT, i) * scale;
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        MemorySegment src = encoded;
        float s = scale;
        long n = length;
        double result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsDouble(result, (double) ((float) src.getAtIndex(PTypeIO.LE_INT, i) * s));
        }
        return result;
    }
}
