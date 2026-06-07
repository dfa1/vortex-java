package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.LongBinaryOperator;

/// Concrete [Array] for I16/U16 primitive columns.
public final class ShortArray implements Array {

    private final DType dtype;
    private final long length;
    final MemorySegment buffer;

    /// Creates a new {@code ShortArray} backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be I16 or U16
    /// @param length number of elements
    /// @param buffer little-endian short data (2 bytes per element)
    public ShortArray(DType dtype, long length, MemorySegment buffer) {
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


    /// Returns the raw signed short value at the given index.
    ///
    /// @param i zero-based index (must be in {@code [0, length)})
    /// @return the signed short value at position {@code i}
    public short getShort(long i) {
        long cap = buffer.byteSize() / PTypeIO.LE_SHORT.byteSize();
        return buffer.getAtIndex(PTypeIO.LE_SHORT, i % cap);
    }

    /// Returns the element at the given index as an {@code int}, widening to unsigned if the dtype is U16.
    ///
    /// @param i zero-based index (must be in {@code [0, length)})
    /// @return the element at position {@code i} as an int (unsigned-widened for U16)
    public int getInt(long i) {
        long cap = buffer.byteSize() / PTypeIO.LE_SHORT.byteSize();
        short raw = buffer.getAtIndex(PTypeIO.LE_SHORT, i % cap);
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U16;
        return unsigned ? Short.toUnsignedInt(raw) : raw;
    }

    /// Folds all elements using the given binary operator and identity value.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to the accumulator and each raw short element
    /// @return the final accumulated result
    public long fold(long identity, LongBinaryOperator op) {
        MemorySegment buf = buffer;
        long n = length;
        long cap = buf.byteSize() / PTypeIO.LE_SHORT.byteSize();
        long result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsLong(result, buf.getAtIndex(PTypeIO.LE_SHORT, i % cap));
        }
        return result;
    }
}
