package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.LongBinaryOperator;

/// Lazy [ShortArray] backed by the `vortex.zigzag` encoded `u16` child segment.
///
/// Decode is deferred to element access: `getShort(i) = (u >>> 1) ^ -(u & 1)`,
/// where `u` is the unsigned short bit pattern at position `i`.
/// Returned by {@link io.github.dfa1.vortex.reader.decode.ZigZagEncodingDecoder} for I16 columns
/// when the source is not a broadcast constant.
///
/// @param dtype   logical I16 type
/// @param length  number of logical elements
/// @param encoded backing `u16` segment (one short per row, zigzag bit pattern)
public record LazyZigZagShortArray(DType dtype, long length, MemorySegment encoded)
        implements ShortArray {

    @Override
    public short getShort(long i) {
        int u = Short.toUnsignedInt(encoded.getAtIndex(PTypeIO.LE_SHORT, i));
        return (short) ((u >>> 1) ^ -(u & 1));
    }

    @Override
    public int getInt(long i) {
        short raw = getShort(i);
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U16;
        return unsigned ? Short.toUnsignedInt(raw) : (int) raw;
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        MemorySegment seg = encoded;
        long n = length;
        long result = identity;
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U16;
        for (long i = 0; i < n; i++) {
            int u = Short.toUnsignedInt(seg.getAtIndex(PTypeIO.LE_SHORT, i));
            short v = (short) ((u >>> 1) ^ -(u & 1));
            result = op.applyAsLong(result, unsigned ? Short.toUnsignedLong(v) : (long) v);
        }
        return result;
    }
}
