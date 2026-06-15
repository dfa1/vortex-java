package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;

/// Lazy [FloatArray] backed by the {@code vortex.alp} encoded {@code i32} child segment.
///
/// Decode is deferred to element access:
/// {@code getFloat(i) = (float) encoded[i] * factorF * factorE}. Two-step multiplication mirrors
/// the Rust reference; see [LazyAlpDoubleArray] for rationale.
///
/// @param dtype   logical F32 type
/// @param length  number of logical elements
/// @param encoded backing {@code i32} segment (one int per row)
/// @param factorF {@code 10^exp_f}
/// @param factorE {@code 10^(-exp_e)}
public record LazyAlpFloatArray(DType dtype, long length, MemorySegment encoded,
                                float factorF, float factorE)
        implements FloatArray {

    @Override
    public float getFloat(long i) {
        return (float) encoded.getAtIndex(PTypeIO.LE_INT, i) * factorF * factorE;
    }
}
