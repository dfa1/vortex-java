package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Buffer-backed [LongArray] — the fallback used when an encoding decoder
/// either materialises the output eagerly or has no lazy variant of its own.
public final class MaterializedLongArray extends AbstractMaterializedArray implements LongArray {

    private final long elementCount;

    /// Creates a new `MaterializedLongArray` backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be I64 or U64
    /// @param length number of elements
    /// @param buffer little-endian long data (8 bytes per element)
    public MaterializedLongArray(DType dtype, long length, MemorySegment buffer) {
        super(dtype, length, buffer);
        this.elementCount = buffer.byteSize() / PTypeIO.LE_LONG.byteSize();
    }

    @Override
    public long getLong(long i) {
        return buffer.getAtIndex(PTypeIO.LE_LONG, length == elementCount ? i : i % elementCount);
    }

    @Override
    public void forEachLong(LongConsumer c) {
        MemorySegment buf = buffer;
        long n = length;
        if (n == elementCount) {
            for (long i = 0; i < n; i++) {
                c.accept(buf.getAtIndex(PTypeIO.LE_LONG, i));
            }
        } else {
            for (long i = 0; i < n; i++) {
                c.accept(buf.getAtIndex(PTypeIO.LE_LONG, i % elementCount));
            }
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        MemorySegment buf = buffer;
        long n = length;
        long result = identity;
        if (n == elementCount) {
            for (long i = 0; i < n; i++) {
                result = op.applyAsLong(result, buf.getAtIndex(PTypeIO.LE_LONG, i));
            }
        } else {
            for (long i = 0; i < n; i++) {
                result = op.applyAsLong(result, buf.getAtIndex(PTypeIO.LE_LONG, i % elementCount));
            }
        }
        return result;
    }
}
