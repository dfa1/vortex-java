package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Concrete [Array] for I64/U64 primitive columns.
public final class LongArray implements Array {

    private final DType dtype;
    private final long length;
    private final MemorySegment buffer;
    // Pre-computed buffer capacity in elements. Equals `length` for normal arrays;
    // smaller for ConstantEncoding (1-element broadcast, length = logical row count).
    // Hoisting it out of every hot-loop iteration is what makes the fast-path
    // branch in fold/forEachLong unswitch-able and vectorizable.
    private final long elementCount;

    /// Creates a new {@code LongArray} backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be I64 or U64
    /// @param length number of elements
    /// @param buffer little-endian long data (8 bytes per element)
    public LongArray(DType dtype, long length, MemorySegment buffer) {
        this.dtype = dtype;
        this.length = length;
        this.buffer = buffer;
        this.elementCount = buffer.byteSize() / PTypeIO.LE_LONG.byteSize();
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

    /// Returns the long value at the given index.
    ///
    /// @param i zero-based index (must be in {@code [0, length)})
    /// @return the long value at position {@code i}
    public long getLong(long i) {
        return buffer.getAtIndex(PTypeIO.LE_LONG, length == elementCount ? i : i % elementCount);
    }

    /// Passes each element to the given consumer in order.
    ///
    /// @param c consumer that receives each long element
    public void forEachLong(LongConsumer c) {
        MemorySegment buf = buffer;
        long n = length;
        if (n == elementCount) {
            for (long i = 0; i < n; i++) {
                c.accept(buf.getAtIndex(PTypeIO.LE_LONG, i));
            }
        } else {
            long cap = elementCount;
            for (long i = 0; i < n; i++) {
                c.accept(buf.getAtIndex(PTypeIO.LE_LONG, i % cap));
            }
        }
    }

    /// Folds all elements using the given binary operator and identity value.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to the accumulator and each long element
    /// @return the final accumulated result
    public long fold(long identity, LongBinaryOperator op) {
        MemorySegment buf = buffer;
        long n = length;
        long result = identity;
        if (n == elementCount) {
            for (long i = 0; i < n; i++) {
                result = op.applyAsLong(result, buf.getAtIndex(PTypeIO.LE_LONG, i));
            }
        } else {
            long cap = elementCount;
            for (long i = 0; i < n; i++) {
                result = op.applyAsLong(result, buf.getAtIndex(PTypeIO.LE_LONG, i % cap));
            }
        }
        return result;
    }
}
