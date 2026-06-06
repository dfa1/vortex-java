package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;

/// Encoder/decoder for {@code vortex.ext} — extension type transparent storage wrapper.
///
/// <p>No buffers, empty metadata. Child slot 0: the storage array encoded/decoded using
/// the extension dtype's storage dtype and same row count.
///
/// <p>Encode: delegates to {@link PrimitiveEncoding} for the storage child, wraps in ext node.
/// Decode: unwraps the single child and returns it directly.
public final class ExtEncoding implements Encoding {

    /// Creates a new {@code ExtEncoding} instance.
    public ExtEncoding() {
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
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Extension ext)) {
            throw new VortexException(EncodingId.VORTEX_EXT, "expected extension dtype, got " + dtype);
        }
        EncodeResult childResult = ctx.lookupEncoding(EncodingId.VORTEX_PRIMITIVE).encode(ext.storageDType(), data, ctx);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_EXT, null, new EncodeNode[]{childResult.rootNode()}, new int[0]);
        return new EncodeResult(root, childResult.buffers(), childResult.statsMin(), childResult.statsMax());
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
