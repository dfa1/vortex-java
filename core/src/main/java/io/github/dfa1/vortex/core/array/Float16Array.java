package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;

/// Concrete [Array] for F16 (IEEE 754 half-precision) columns.
/// Wire format: little-endian shorts (2 bytes/element). Element access
/// widens to `float` via [Float#float16ToFloat].
public final class Float16Array implements Array {

    private final DType dtype;
    private final long length;
    private final MemorySegment buffer;

    /// Creates a new {@code Float16Array} backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be F16
    /// @param length number of elements
    /// @param buffer little-endian half-precision float data (2 bytes per element)
    public Float16Array(DType dtype, long length, MemorySegment buffer) {
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

    /// Returns the raw backing segment (little-endian, 2 bytes per element).
    ///
    /// @return the backing {@link MemorySegment}
    public MemorySegment segment() {
        return buffer;
    }

    /// Returns the element at the given index widened to a single-precision float.
    ///
    /// @param i zero-based index (must be in {@code [0, length)})
    /// @return the half-precision value at position {@code i} converted to {@code float}
    public float getFloat(long i) {
        return Float.float16ToFloat(buffer.getAtIndex(PTypeIO.LE_SHORT, i));
    }
}
