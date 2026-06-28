package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// Buffer-backed [IntArray] — the fallback used when an encoding decoder
/// either materializes the output eagerly or has no lazy variant of its own.
public final class MaterializedIntArray extends AbstractMaterializedArray implements IntArray {

    /// Creates a new `MaterializedIntArray` backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be I32 or U32
    /// @param length number of elements
    /// @param buffer little-endian int data (4 bytes per element)
    public MaterializedIntArray(DType dtype, long length, MemorySegment buffer) {
        super(dtype, length, buffer);
    }

    @Override
    public int getInt(long i) {
        return buffer.getAtIndex(PTypeIO.LE_INT, length == elementCount ? i : i % elementCount);
    }

    @Override
    public void forEachInt(IntConsumer c) {
        MemorySegment buf = buffer;
        long n = length;
        if (n == elementCount) {
            for (long i = 0; i < n; i++) {
                c.accept(buf.getAtIndex(PTypeIO.LE_INT, i));
            }
        } else {
            for (long i = 0; i < n; i++) {
                c.accept(buf.getAtIndex(PTypeIO.LE_INT, i % elementCount));
            }
        }
    }

    @Override
    public int fold(int identity, IntBinaryOperator op) {
        MemorySegment buf = buffer;
        long n = length;
        int result = identity;
        if (n == elementCount) {
            for (long i = 0; i < n; i++) {
                result = op.applyAsInt(result, buf.getAtIndex(PTypeIO.LE_INT, i));
            }
        } else {
            for (long i = 0; i < n; i++) {
                result = op.applyAsInt(result, buf.getAtIndex(PTypeIO.LE_INT, i % elementCount));
            }
        }
        return result;
    }
}
