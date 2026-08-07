package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/// Zero-copy [BoolArray] over a `vortex.bytebool` buffer: one byte per row, non-zero meaning
/// `true`.
///
/// `vortex.bytebool` is the one boolean encoding whose source buffer is already directly
/// indexable per row, so decode has nothing to do — this reads the mmapped bytes in place
/// instead of allocating an `n/8`-byte bitmap and running a read-modify-write over every row
/// to fill it.
///
/// [#materialize(java.lang.foreign.SegmentAllocator)] still hands out the LSB-first bitmap the
/// rest of the reader speaks, via [BoolArray]'s default — the same packing loop this decode
/// used to run eagerly, now paid only by callers that actually want a bitmap.
///
/// [Array#segmentIfPresent()] is left empty (the interface default): the backing segment is a
/// byte-per-row buffer, not the bit-packed layout a caller asking for a bool array's segment
/// expects, so handing it over would be misread.
///
/// @param dtype  logical [DType.Bool] type
/// @param length number of logical rows
/// @param bytes  one byte per row; non-zero is `true`. Must hold at least `length` bytes —
///               [io.github.dfa1.vortex.reader.decode.ByteBoolEncodingDecoder] checks that
///               once, so the accessors here do not re-check it per row
public record LazyByteBoolArray(DType dtype, long length, MemorySegment bytes) implements BoolArray {

    @Override
    public boolean getBoolean(long i) {
        Objects.checkIndex(i, length);
        return bytes.get(ValueLayout.JAVA_BYTE, i) != 0;
    }

    @Override
    public void forEachBoolean(BooleanConsumer c) {
        long n = length;
        for (long i = 0; i < n; i++) {
            c.accept(bytes.get(ValueLayout.JAVA_BYTE, i) != 0);
        }
    }
}
