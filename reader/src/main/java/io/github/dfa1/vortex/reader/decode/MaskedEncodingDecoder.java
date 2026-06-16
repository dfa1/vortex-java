package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.encoding.EncodingId;

/// Read-only decoder for `vortex.masked` — payload child + optional validity bitmap child.
public final class MaskedEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public MaskedEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_MASKED;
    }

    @Override
    public boolean accepts(DType dtype) {
        return false;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        if (ctx.node().bufferIndices().length != 0) {
            throw new VortexException(EncodingId.VORTEX_MASKED,
                    "expected 0 buffers, got " + ctx.node().bufferIndices().length);
        }
        int numChildren = ctx.node().children().length;
        if (numChildren < 1 || numChildren > 2) {
            throw new VortexException(EncodingId.VORTEX_MASKED,
                    "expected 1 or 2 children, got " + numChildren);
        }

        Array child = ctx.decodeChild(0, ctx.dtype().withNullable(false), ctx.rowCount());

        BoolArray validity = null;
        if (numChildren == 2) {
            Array validityArray = ctx.decodeChild(1, new DType.Bool(false), ctx.rowCount());
            if (!(validityArray instanceof BoolArray ba)) {
                throw new VortexException(EncodingId.VORTEX_MASKED,
                        "validity child decoded to unexpected type: " + validityArray.getClass().getSimpleName());
            }
            validity = ba;
        }

        return new MaskedArray(child, validity);
    }
}
