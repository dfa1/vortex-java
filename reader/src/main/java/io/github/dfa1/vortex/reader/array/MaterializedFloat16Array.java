package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;

/// Buffer-backed [Float16Array] — the fallback used when an encoding decoder
/// either materialises the output eagerly or has no lazy variant of its own.
public final class MaterializedFloat16Array implements Float16Array {

    private final DType dtype;
    private final long length;
    private final MemorySegment buffer;

    /// Creates a new `MaterializedFloat16Array` backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be F16
    /// @param length number of elements
    /// @param buffer little-endian half-precision float data (2 bytes per element)
    public MaterializedFloat16Array(DType dtype, long length, MemorySegment buffer) {
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

    MemorySegment buffer() {
        return buffer;
    }

    @Override
    public float getFloat(long i) {
        return Float.float16ToFloat(buffer.getAtIndex(PTypeIO.LE_SHORT, i));
    }
}
