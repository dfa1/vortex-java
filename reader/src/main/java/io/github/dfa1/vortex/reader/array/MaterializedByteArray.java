package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.util.Optional;
import java.util.function.LongBinaryOperator;

/// Buffer-backed [ByteArray] — the fallback used when an encoding decoder
/// either materialises the output eagerly or has no lazy variant of its own.
public final class MaterializedByteArray implements ByteArray {

    private final DType dtype;
    private final long length;
    private final MemorySegment buffer;
    private final long elementCount;

    /// Constructs a `MaterializedByteArray` backed by the given buffer.
    ///
    /// @param dtype  logical type, must be a [io.github.dfa1.vortex.core.DType.Primitive] with ptype I8 or U8
    /// @param length number of logical elements
    /// @param buffer raw byte data (one byte per element)
    public MaterializedByteArray(DType dtype, long length, MemorySegment buffer) {
        this.dtype = dtype;
        this.length = length;
        this.buffer = buffer;
        this.elementCount = buffer.byteSize();
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public long length() {
        return length;
    }

    /// Returns the backing buffer directly — already a contiguous one-byte-per-element
    /// segment, so no copy or allocation is needed.
    ///
    /// @param arena unused; the existing buffer is returned as-is
    /// @return the backing byte segment
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        return buffer;
    }

    @Override
    public Optional<MemorySegment> segmentIfPresent() {
        return Optional.of(buffer);
    }

    @Override
    public byte getByte(long i) {
        return buffer.get(ValueLayout.JAVA_BYTE, length == elementCount ? i : i % elementCount);
    }

    @Override
    public int getInt(long i) {
        byte raw = buffer.get(ValueLayout.JAVA_BYTE, length == elementCount ? i : i % elementCount);
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U8;
        return unsigned ? Byte.toUnsignedInt(raw) : raw;
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        MemorySegment buf = buffer;
        long n = length;
        long result = identity;
        if (n == elementCount) {
            for (long i = 0; i < n; i++) {
                result = op.applyAsLong(result, buf.get(ValueLayout.JAVA_BYTE, i));
            }
        } else {
            for (long i = 0; i < n; i++) {
                result = op.applyAsLong(result, buf.get(ValueLayout.JAVA_BYTE, i % elementCount));
            }
        }
        return result;
    }
}
