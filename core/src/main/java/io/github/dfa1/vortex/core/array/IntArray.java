package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// Concrete [Array] for I32/U32 primitive columns.
public final class IntArray implements Array {

    private final DType dtype;
    private final long length;
    private final MemorySegment buffer;
    private final ArrayStats stats;

    /// Creates a new {@code IntArray} backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be I32 or U32
    /// @param length number of elements
    /// @param buffer little-endian int data (4 bytes per element)
    /// @param stats  per-array statistics, or {@link io.github.dfa1.vortex.core.ArrayStats#empty()} if unknown
    public IntArray(DType dtype, long length, MemorySegment buffer, ArrayStats stats) {
        this.dtype = dtype;
        this.length = length;
        this.buffer = buffer;
        this.stats = stats;
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public long length() {
        return length;
    }

    /// Returns per-array statistics.
    ///
    /// @return array statistics
    public ArrayStats stats() {
        return stats;
    }

    @Override
    public MemorySegment buffer(int i) {
        if (i != 0) {
            throw new IndexOutOfBoundsException(i);
        }
        return buffer;
    }

    /// Returns the int value at the given index.
    ///
    /// @param i zero-based index (must be in {@code [0, length)})
    /// @return the int value at position {@code i}
    public int getInt(long i) {
        long cap = buffer.byteSize() / PTypeIO.LE_INT.byteSize();
        return buffer.getAtIndex(PTypeIO.LE_INT, i % cap);
    }

    /// Passes each element to the given consumer in order.
    ///
    /// @param c consumer that receives each int element
    public void forEachInt(IntConsumer c) {
        MemorySegment buf = buffer;
        long n = length;
        long cap = buf.byteSize() / PTypeIO.LE_INT.byteSize();
        for (long i = 0; i < n; i++) {
            c.accept(buf.getAtIndex(PTypeIO.LE_INT, i % cap));
        }
    }

    /// Folds all elements using the given binary operator and identity value.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to the accumulator and each int element
    /// @return the final accumulated result
    public int fold(int identity, IntBinaryOperator op) {
        long cap = buffer.byteSize() / PTypeIO.LE_INT.byteSize();
        int result = identity;
        for (long i = 0; i < length; i++) {
            result = op.applyAsInt(result, buffer.getAtIndex(PTypeIO.LE_INT, i % cap));
        }
        return result;
    }
}
