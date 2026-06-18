package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.function.LongBinaryOperator;

/// Lazy [ByteArray] backed by the `fastlanes.for` encoded `i8`/`u8` child segment.
///
/// Decode is deferred to element access: `getByte(i) = encoded[i] + ref` (byte-wrapping addition).
/// Returned by [io.github.dfa1.vortex.reader.decode.FrameOfReferenceEncodingDecoder] when
/// `ref != 0` for I8 or U8 columns.
///
/// @param dtype   logical I8/U8 type
/// @param length  number of logical elements
/// @param encoded backing `i8` segment (one byte per row, frame-of-reference residual)
/// @param ref     reference byte added to each encoded element (frame value)
public record LazyForByteArray(DType dtype, long length, MemorySegment encoded, byte ref)
        implements ByteArray {

    @Override
    public byte getByte(long i) {
        return (byte) (encoded.get(ValueLayout.JAVA_BYTE, i) + ref);
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        MemorySegment seg = encoded;
        long n = length;
        long result = identity;
        byte r = ref;
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U8;
        for (long i = 0; i < n; i++) {
            byte v = (byte) (seg.get(ValueLayout.JAVA_BYTE, i) + r);
            result = op.applyAsLong(result, unsigned ? Byte.toUnsignedLong(v) : (long) v);
        }
        return result;
    }
}
