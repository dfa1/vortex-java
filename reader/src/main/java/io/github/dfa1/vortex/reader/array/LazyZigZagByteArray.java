package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.function.LongBinaryOperator;

/// Lazy [ByteArray] backed by the `vortex.zigzag` encoded `u8` child segment.
///
/// Decode is deferred to element access: `getByte(i) = (u >>> 1) ^ -(u & 1)`,
/// where `u` is the unsigned byte bit pattern at position `i`.
/// Returned by [io.github.dfa1.vortex.reader.decode.ZigZagEncodingDecoder] for I8 columns
/// when the source is not a broadcast constant.
///
/// @param dtype   logical I8 type
/// @param length  number of logical elements
/// @param encoded backing `u8` segment (one byte per row, zigzag bit pattern)
public record LazyZigZagByteArray(DType dtype, long length, MemorySegment encoded)
        implements ByteArray {

    @Override
    public byte getByte(long i) {
        int u = Byte.toUnsignedInt(encoded.get(ValueLayout.JAVA_BYTE, i));
        return (byte) ((u >>> 1) ^ -(u & 1));
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        MemorySegment seg = encoded;
        long n = length;
        long result = identity;
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U8;
        for (long i = 0; i < n; i++) {
            int u = Byte.toUnsignedInt(seg.get(ValueLayout.JAVA_BYTE, i));
            byte v = (byte) ((u >>> 1) ^ -(u & 1));
            result = op.applyAsLong(result, unsigned ? Byte.toUnsignedLong(v) : (long) v);
        }
        return result;
    }
}
