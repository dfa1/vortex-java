package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyByteBoolArray;

import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.bytebool` — one byte per boolean, read in place.
public final class ByteBoolEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_BYTEBOOL;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        long n = ctx.rowCount();
        MemorySegment bytes = ctx.buffer(0);
        // The buffer comes straight from the file and holds one byte per row, so a shorter one
        // is malformed. Checked once here, in O(1), rather than per row: it keeps
        // LazyByteBoolArray's accessor uniform, and a crafted file fails as a VortexException
        // instead of a raw IndexOutOfBoundsException on whichever row runs off the end
        // (ADR 0003) — which is what the eager packing loop this replaces did.
        if (bytes.byteSize() < n) {
            throw new VortexException(EncodingId.VORTEX_BYTEBOOL,
                    "bytebool buffer of " + bytes.byteSize() + " byte(s) is shorter than the "
                            + n + " declared row(s)");
        }
        return new LazyByteBoolArray(ctx.dtype(), n, bytes);
    }
}
