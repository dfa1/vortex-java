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

    /// Creates a new {@code LongArray} backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be I64 or U64
    /// @param length number of elements
    /// @param buffer little-endian long data (8 bytes per element)
    public LongArray(DType dtype, long length, MemorySegment buffer) {
        this.dtype = dtype;
        this.length = length;
        this.buffer = buffer;
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public MemorySegment buffer(int i) {
        if (i != 0) {
            throw new IndexOutOfBoundsException(i);
        }
        return buffer;
    }

    /// Returns the long value at the given index.
    ///
    /// @param i zero-based index (must be in {@code [0, length)})
    /// @return the long value at position {@code i}
    public long getLong(long i) {
        long cap = buffer.byteSize() / PTypeIO.LE_LONG.byteSize();
        return buffer.getAtIndex(PTypeIO.LE_LONG, i % cap);
    }

    /// Passes each element to the given consumer in order.
    ///
    /// @param c consumer that receives each long element
    public void forEachLong(LongConsumer c) {
        MemorySegment buf = buffer;
        long n = length;
        long cap = buf.byteSize() / PTypeIO.LE_LONG.byteSize();
        for (long i = 0; i < n; i++) {
            c.accept(buf.getAtIndex(PTypeIO.LE_LONG, i % cap));
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
        long cap = buf.byteSize() / PTypeIO.LE_LONG.byteSize();
        long result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsLong(result, buf.getAtIndex(PTypeIO.LE_LONG, i % cap));
        }
        return result;
    }
}
