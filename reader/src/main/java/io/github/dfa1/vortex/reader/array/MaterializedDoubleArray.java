package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Buffer-backed [DoubleArray] — the fallback used when an encoding decoder
/// either materialises the output eagerly or has no lazy variant of its own.
public final class MaterializedDoubleArray implements DoubleArray {

    private final DType dtype;
    private final long length;
    private final MemorySegment buffer;
    // Pre-computed buffer capacity in elements. Equals `length` for normal arrays;
    // smaller for ConstantEncoding (1-element broadcast, length = logical row count).
    // Hoisting it out of every hot-loop iteration is what makes the fast-path
    // branch in fold/forEachDouble unswitch-able and vectorizable.
    private final long elementCount;

    /// Constructs a {@code MaterializedDoubleArray} backed by the given buffer.
    ///
    /// @param dtype  logical type, must be a {@link io.github.dfa1.vortex.core.DType.Primitive} with ptype F64
    /// @param length number of logical elements
    /// @param buffer raw double data (8 bytes per element, little-endian)
    public MaterializedDoubleArray(DType dtype, long length, MemorySegment buffer) {
        this.dtype = dtype;
        this.length = length;
        this.buffer = buffer;
        this.elementCount = buffer.byteSize() / PTypeIO.LE_DOUBLE.byteSize();
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public long length() {
        return length;
    }

    MemorySegment buffer() {
        return buffer;
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
