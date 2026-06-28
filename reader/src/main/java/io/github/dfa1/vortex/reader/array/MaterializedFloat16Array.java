package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.PTypeIO;

import java.lang.foreign.MemorySegment;

/// Buffer-backed [Float16Array] — the fallback used when an encoding decoder
/// either materializes the output eagerly or has no lazy variant of its own.
public final class MaterializedFloat16Array extends AbstractMaterializedArray implements Float16Array {

    /// Creates a new `MaterializedFloat16Array` backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be F16
    /// @param length number of elements
    /// @param buffer little-endian half-precision float data (2 bytes per element)
    public MaterializedFloat16Array(DType dtype, long length, MemorySegment buffer) {
        super(dtype, length, buffer);
    }

    @Override
    public float getFloat(long i) {
        return Float.float16ToFloat(buffer.getAtIndex(PTypeIO.LE_SHORT, i));
    }

    @Override
    public Array limited(long rows) {
        // f16 is 2 bytes per element; zero-copy slice of the backing segment.
        return new MaterializedFloat16Array(dtype, rows, buffer.asSlice(0, rows * 2));
    }
}
