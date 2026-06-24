package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Buffer-backed [DoubleArray] — the fallback used when an encoding decoder
/// either materialises the output eagerly or has no lazy variant of its own.
public final class MaterializedDoubleArray extends AbstractMaterializedArray implements DoubleArray {

    /// Constructs a `MaterializedDoubleArray` backed by the given buffer.
    ///
    /// @param dtype  logical type, must be a [io.github.dfa1.vortex.core.model.DType.Primitive] with ptype F64
    /// @param length number of logical elements
    /// @param buffer raw double data (8 bytes per element, little-endian)
    public MaterializedDoubleArray(DType dtype, long length, MemorySegment buffer) {
        super(dtype, length, buffer);
    }

    @Override
    public double getDouble(long i) {
        return buffer.getAtIndex(PTypeIO.LE_DOUBLE, length == elementCount ? i : i % elementCount);
    }

    @Override
    public void forEachDouble(DoubleConsumer c) {
        MemorySegment buf = buffer;
        long n = length;
        if (n == elementCount) {
            for (long i = 0; i < n; i++) {
                c.accept(buf.getAtIndex(PTypeIO.LE_DOUBLE, i));
            }
        } else {
            for (long i = 0; i < n; i++) {
                c.accept(buf.getAtIndex(PTypeIO.LE_DOUBLE, i % elementCount));
            }
        }
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        MemorySegment buf = buffer;
        long n = length;
        double result = identity;
        if (n == elementCount) {
            for (long i = 0; i < n; i++) {
                result = op.applyAsDouble(result, buf.getAtIndex(PTypeIO.LE_DOUBLE, i));
            }
        } else {
            for (long i = 0; i < n; i++) {
                result = op.applyAsDouble(result, buf.getAtIndex(PTypeIO.LE_DOUBLE, i % elementCount));
            }
        }
        return result;
    }
}
