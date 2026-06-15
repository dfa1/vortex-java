package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for {@code vortex.bytebool} — packs the input byte buffer into the
/// bit-packed {@link BoolArray} layout used by {@code vortex.bool}.
public final class ByteBoolEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public ByteBoolEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_BYTEBOOL;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Bool;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        long n = ctx.rowCount();
        MemorySegment bytes = ctx.buffer(0);
        long packedBytes = (n + 7) >>> 3;
        MemorySegment packed = ctx.arena().allocate(packedBytes > 0 ? packedBytes : 1);
        for (long i = 0; i < n; i++) {
            if (bytes.get(ValueLayout.JAVA_BYTE, i) != 0) {
                long byteIdx = i >>> 3;
                byte cur = packed.get(ValueLayout.JAVA_BYTE, byteIdx);
                packed.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) ((cur & 0xff) | (1 << (i & 7))));
            }
        }
        return new MaterializedBoolArray(ctx.dtype(), n, packed);
    }
}
