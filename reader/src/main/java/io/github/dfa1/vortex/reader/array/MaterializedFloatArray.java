package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.DoubleBinaryOperator;

/// Buffer-backed [FloatArray] — the fallback used when an encoding decoder
/// either materialises the output eagerly or has no lazy variant of its own.
public final class MaterializedFloatArray extends AbstractMaterializedArray implements FloatArray {

    /// Creates a new `MaterializedFloatArray` backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be F32
    /// @param length number of elements
    /// @param buffer little-endian float data (4 bytes per element)
    public MaterializedFloatArray(DType dtype, long length, MemorySegment buffer) {
        super(dtype, length, buffer);
    }

    @Override
    public float getFloat(long i) {
        return buffer.getAtIndex(PTypeIO.LE_FLOAT, length == elementCount ? i : i % elementCount);
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        MemorySegment buf = buffer;
        long n = length;
        double result = identity;
        if (n == elementCount) {
            for (long i = 0; i < n; i++) {
                result = op.applyAsDouble(result, buf.getAtIndex(PTypeIO.LE_FLOAT, i));
            }
        } else {
            for (long i = 0; i < n; i++) {
                result = op.applyAsDouble(result, buf.getAtIndex(PTypeIO.LE_FLOAT, i % elementCount));
            }
        }
        return result;
    }
}
