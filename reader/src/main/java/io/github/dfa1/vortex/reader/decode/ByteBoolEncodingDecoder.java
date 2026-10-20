package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyByteBoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;

import java.lang.foreign.MemorySegment;

/// Read-only decoder for `vortex.bytebool` — one byte per boolean, read in place.
public final class ByteBoolEncodingDecoder implements EncodingDecoder {

    /// Creates the decoder.
    public ByteBoolEncodingDecoder() {
    }

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
        // A nullable column carries its validity as an optional single child (Rust: 0 or 1 children),
        // not under a vortex.masked wrapper. Ignoring it read every null row back as false. As for
        // vortex.list, the declared nullability is authoritative.
        int children = ctx.node().children().length;
        if (children > 1) {
            throw new VortexException(EncodingId.VORTEX_BYTEBOOL, "expected 0 or 1 children, got " + children);
        }
        if (children == 0 || !ctx.dtype().nullable()) {
            return new LazyByteBoolArray(ctx.dtype(), n, bytes);
        }
        Array validity = ctx.decodeChild(0, DType.BOOL, n);
        return new MaskedArray(new LazyByteBoolArray(ctx.dtype().withNullable(false), n, bytes),
                MaskedArray.requireBoolArray(validity, EncodingId.VORTEX_BYTEBOOL, "validity child"));
    }
}
