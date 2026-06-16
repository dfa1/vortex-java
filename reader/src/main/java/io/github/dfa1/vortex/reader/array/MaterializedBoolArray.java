package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Buffer-backed [BoolArray] — the fallback used when an encoding decoder
/// either materialises the output eagerly or has no lazy variant of its own.
public final class MaterializedBoolArray implements BoolArray {

    private final DType dtype;
    private final long length;
    private final MemorySegment buffer;

    /// Constructs a `MaterializedBoolArray` backed by the given bit-packed buffer.
    ///
    /// @param dtype  logical type, must be {@link io.github.dfa1.vortex.core.DType.Bool}
    /// @param length number of logical boolean elements
    /// @param buffer bit-packed boolean data (LSB-first, one byte per 8 elements)
    public MaterializedBoolArray(DType dtype, long length, MemorySegment buffer) {
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
    public boolean getBoolean(long i) {
        byte b = buffer.get(ValueLayout.JAVA_BYTE, i >>> 3);
        return ((b & 0xff) & (1 << (i & 7))) != 0;
    }
}
