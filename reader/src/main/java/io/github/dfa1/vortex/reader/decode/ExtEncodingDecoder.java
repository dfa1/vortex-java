package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.encoding.EncodingId;

/// Read-only decoder for `vortex.ext` — unwraps the storage-array child.
public final class ExtEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public ExtEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_EXT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Extension;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        if (!(ctx.dtype() instanceof DType.Extension ext)) {
            throw new VortexException(EncodingId.VORTEX_EXT, "expected extension dtype, got " + ctx.dtype());
        }
        long n = ctx.rowCount();
        ArrayNode childNode = ctx.node().children()[0];
        DecodeContext childCtx = new DecodeContext(
                childNode, ext.storageDType(), n,
                ctx.segmentBuffers(), ctx.registry(), ctx.arena());
        return ctx.registry().decode(childCtx);
    }
}
