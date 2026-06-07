package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.function.LongBinaryOperator;

/// Concrete [Array] for I8/U8 primitive columns.
public final class ByteArray implements Array {

    private final DType dtype;
    private final long length;
    private final MemorySegment buffer;

    /// Constructs a {@code ByteArray} backed by the given buffer.
    ///
    /// @param dtype  logical type, must be a {@link io.github.dfa1.vortex.core.DType.Primitive} with ptype I8 or U8
    /// @param length number of logical elements
    /// @param buffer raw byte data (one byte per element, little-endian)
    public ByteArray(DType dtype, long length, MemorySegment buffer) {
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

    /// Returns the raw backing segment (one byte per element).
    ///
    /// @return the backing {@link MemorySegment}
    public MemorySegment segment() {
        return buffer;
    }

    /// Returns the raw byte value at the given logical index.
    ///
    /// @param i zero-based logical index (must be in {@code [0, length)})
    /// @return the raw signed byte value at position {@code i}
    public byte getByte(long i) {
        return buffer.get(ValueLayout.JAVA_BYTE, i % buffer.byteSize());
    }

    /// Returns the int value at the given logical index, applying unsigned widening for U8 columns.
    ///
    /// @param i zero-based logical index (must be in {@code [0, length)})
    /// @return the value at position {@code i} as a signed int (U8 values are zero-extended)
    public int getInt(long i) {
        byte raw = buffer.get(ValueLayout.JAVA_BYTE, i % buffer.byteSize());
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U8;
        return unsigned ? Byte.toUnsignedInt(raw) : raw;
    }

    /// Reduces all elements to a single long using the supplied operator.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to accumulator and each element in order
    /// @return the final accumulated value
    public long fold(long identity, LongBinaryOperator op) {
        MemorySegment buf = buffer;
        long n = length;
        long cap = buf.byteSize();
        long result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsLong(result, buf.get(ValueLayout.JAVA_BYTE, i % cap));
        }
        return result;
    }
}
