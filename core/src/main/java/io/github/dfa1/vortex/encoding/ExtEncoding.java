package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;

import java.util.List;

/// Encoder/decoder for {@code vortex.ext} — extension type transparent storage wrapper.
///
/// <p>No buffers, empty metadata. Child slot 0: the storage array encoded/decoded using
/// the extension dtype's storage dtype and same row count.
///
/// <p>Encode (non-cascading {@link #encode}): picks the first encoding from the static
/// fallback list that accepts the storage dtype ({@link PrimitiveEncoding} for numeric
/// storage, {@link FixedSizeListEncoding} for UUID-style fixed-size byte storage) and
/// wraps its result in an ext node.
///
/// <p>Encode (cascading {@link #encodeCascade}): exposes the storage child as an open
/// {@link ChildSlot} so the {@link CascadingCompressor} can pick the best encoding
/// (FoR/Bitpacked/RLE/ALP/etc.) for numeric storage arrays.
///
/// <p>Decode: unwraps the single child and returns it directly.
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

    private static final List<Encoding> STORAGE_FALLBACK = List.of(
            new PrimitiveEncoding(),
            new FixedSizeListEncoding());

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Extension ext)) {
            throw new VortexException(EncodingId.VORTEX_EXT, "expected extension dtype, got " + dtype);
        }
        DType storage = ext.storageDType();
        Encoding storageEncoding = null;
        for (Encoding enc : STORAGE_FALLBACK) {
            if (enc.accepts(storage)) {
                storageEncoding = enc;
                break;
            }
        }
        if (storageEncoding == null) {
            throw new VortexException(EncodingId.VORTEX_EXT, "no storage encoding for " + storage);
        }
        EncodeResult childResult = storageEncoding.encode(storage, data, ctx);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_EXT, null, new EncodeNode[]{childResult.rootNode()}, new int[0]);
        return new EncodeResult(root, childResult.buffers(), childResult.statsMin(), childResult.statsMax());
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Extension ext)) {
            throw new VortexException(EncodingId.VORTEX_EXT, "expected extension dtype, got " + dtype);
        }
        EncodeNode partialRoot = new EncodeNode(EncodingId.VORTEX_EXT, null, new EncodeNode[1], new int[0]);
        ChildSlot slot = new ChildSlot(ext.storageDType(), data, 0);
        return new CascadeStep(partialRoot, List.of(), List.of(slot), null, null, true);
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
