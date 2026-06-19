package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.LongBinaryOperator;

/// Buffer-backed [ShortArray] — the fallback used when an encoding decoder
/// either materialises the output eagerly or has no lazy variant of its own.
public final class MaterializedShortArray implements ShortArray {

    private final DType dtype;
    private final long length;
    private final MemorySegment buffer;
    private final long elementCount;

    /// Creates a new `MaterializedShortArray` backed by the given memory segment.
    ///
    /// @param dtype  logical type, must be I16 or U16
    /// @param length number of elements
    /// @param buffer little-endian short data (2 bytes per element)
    public MaterializedShortArray(DType dtype, long length, MemorySegment buffer) {
        this.dtype = dtype;
        this.length = length;
        this.buffer = buffer;
        this.elementCount = buffer.byteSize() / PTypeIO.LE_SHORT.byteSize();
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

    /// Returns the backing buffer directly — already a contiguous little-endian
    /// `i16` segment, so no copy or allocation is needed.
    ///
    /// @param arena unused; the existing buffer is returned as-is
    /// @return the backing little-endian `i16` segment
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        return buffer;
    }

    @Override
    public short getShort(long i) {
        return buffer.getAtIndex(PTypeIO.LE_SHORT, length == elementCount ? i : i % elementCount);
    }

    @Override
    public int getInt(long i) {
        short raw = buffer.getAtIndex(PTypeIO.LE_SHORT, length == elementCount ? i : i % elementCount);
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U16;
        return unsigned ? Short.toUnsignedInt(raw) : raw;
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        MemorySegment buf = buffer;
        long n = length;
        long result = identity;
        if (n == elementCount) {
            for (long i = 0; i < n; i++) {
                result = op.applyAsLong(result, buf.getAtIndex(PTypeIO.LE_SHORT, i));
            }
        } else {
            for (long i = 0; i < n; i++) {
                result = op.applyAsLong(result, buf.getAtIndex(PTypeIO.LE_SHORT, i % elementCount));
            }
        }
        return result;
    }
}
