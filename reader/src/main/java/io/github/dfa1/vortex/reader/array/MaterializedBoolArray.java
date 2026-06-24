package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Buffer-backed [BoolArray] — the fallback used when an encoding decoder
/// either materialises the output eagerly or has no lazy variant of its own.
public final class MaterializedBoolArray extends AbstractMaterializedArray implements BoolArray {

    /// Constructs a `MaterializedBoolArray` backed by the given bit-packed buffer.
    ///
    /// @param dtype  logical type, must be [io.github.dfa1.vortex.core.model.DType.Bool]
    /// @param length number of logical boolean elements
    /// @param buffer bit-packed boolean data (LSB-first, one byte per 8 elements)
    public MaterializedBoolArray(DType dtype, long length, MemorySegment buffer) {
        super(dtype, length, buffer);
    }

    @Override
    public boolean getBoolean(long i) {
        byte b = buffer.get(ValueLayout.JAVA_BYTE, i >>> 3);
        return ((b & 0xff) & (1 << (i & 7))) != 0;
    }
}
