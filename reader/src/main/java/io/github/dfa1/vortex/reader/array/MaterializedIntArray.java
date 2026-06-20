package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Optional;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// Buffer-backed [IntArray] — the fallback used when an encoding decoder
/// either materialises the output eagerly or has no lazy variant of its own.
public final class MaterializedIntArray implements IntArray {

    private final DType dtype;
    private final long length;
    private final MemorySegment buffer;
    private final long elementCount;

    /// Creates a new `MaterializedIntArray` backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be I32 or U32
    /// @param length number of elements
    /// @param buffer little-endian int data (4 bytes per element)
    public MaterializedIntArray(DType dtype, long length, MemorySegment buffer) {
        this.dtype = dtype;
        this.length = length;
        this.buffer = buffer;
        this.elementCount = buffer.byteSize() / PTypeIO.LE_INT.byteSize();
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public long length() {
        return length;
    }

    /// Returns the backing buffer directly — already a contiguous little-endian
    /// `i32` segment, so no copy or allocation is needed.
    ///
    /// @param arena unused; the existing buffer is returned as-is
    /// @return the backing little-endian `i32` segment
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        return buffer;
    }

    @Override
    public Optional<MemorySegment> segmentIfPresent() {
        return Optional.of(buffer);
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
