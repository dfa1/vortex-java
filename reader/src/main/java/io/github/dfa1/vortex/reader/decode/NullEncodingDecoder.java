package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.core.model.EncodingId;

/// Read-only decoder for `vortex.null` (all-null arrays).
public final class NullEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_NULL;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return new NullArray(ctx.dtype(), ctx.rowCount());
    }
}
