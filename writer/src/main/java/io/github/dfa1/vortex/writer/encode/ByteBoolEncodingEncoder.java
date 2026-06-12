package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.EncodingId;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Write-only encoder for {@code vortex.bytebool} — one byte per boolean element.
public final class ByteBoolEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public ByteBoolEncodingEncoder() {
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
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        boolean[] bools = (boolean[]) data;
        MemorySegment seg = ctx.arena().allocate(bools.length);
        for (int i = 0; i < bools.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, bools[i] ? (byte) 1 : (byte) 0);
        }
        return EncodeResult.simple(encodingId(), seg);
    }
}
