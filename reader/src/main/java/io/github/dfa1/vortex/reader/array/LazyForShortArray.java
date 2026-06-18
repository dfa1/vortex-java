package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.LongBinaryOperator;

/// Lazy [ShortArray] backed by the `fastlanes.for` encoded `i16`/`u16` child segment.
///
/// Decode is deferred to element access: `getShort(i) = encoded[i] + ref` (short-wrapping addition).
/// Returned by [io.github.dfa1.vortex.reader.decode.FrameOfReferenceEncodingDecoder] when
/// `ref != 0` for I16 or U16 columns.
///
/// @param dtype   logical I16/U16 type
/// @param length  number of logical elements
/// @param encoded backing `i16` segment (one short per row, frame-of-reference residual)
/// @param ref     reference short added to each encoded element (frame value)
public record LazyForShortArray(DType dtype, long length, MemorySegment encoded, short ref)
        implements ShortArray {

    @Override
    public short getShort(long i) {
        return (short) (encoded.getAtIndex(PTypeIO.LE_SHORT, i) + ref);
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        MemorySegment seg = encoded;
        long n = length;
        long result = identity;
        short r = ref;
        boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U16;
        for (long i = 0; i < n; i++) {
            short v = (short) (seg.getAtIndex(PTypeIO.LE_SHORT, i) + r);
            result = op.applyAsLong(result, unsigned ? Short.toUnsignedLong(v) : (long) v);
        }
        return result;
    }
}
