package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;

import java.util.List;

/// Write-only encoder for `vortex.ext` — wraps a storage-array encode in an ext node.
public final class ExtEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public ExtEncodingEncoder() {
    }

    private static final List<EncodingEncoder> STORAGE_FALLBACK = List.of(
            new PrimitiveEncodingEncoder(),
            new FixedSizeListEncodingEncoder());

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
        DType storage = ext.storageDType();
        EncodeResult childResult;
        if (data instanceof NullableData) {
            childResult = new MaskedEncodingEncoder().encode(storage, data, ctx);
        } else {
            EncodingEncoder storageEncoder = null;
            for (EncodingEncoder enc : STORAGE_FALLBACK) {
                if (enc.accepts(storage)) {
                    storageEncoder = enc;
                    break;
                }
            }
            if (storageEncoder == null) {
                throw new VortexException(EncodingId.VORTEX_EXT, "no storage encoder for " + storage);
            }
            childResult = storageEncoder.encode(storage, data, ctx);
        }
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_EXT, null, new EncodeNode[]{childResult.rootNode()}, new int[0]);
        return new EncodeResult(root, childResult.buffers(), childResult.statsMin(), childResult.statsMax());
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Extension ext)) {
            throw new VortexException(EncodingId.VORTEX_EXT, "expected extension dtype, got " + dtype);
        }
        if (data instanceof NullableData) {
            return CascadeStep.terminal(encode(dtype, data, ctx));
        }
        EncodeNode partialRoot = new EncodeNode(EncodingId.VORTEX_EXT, null, new EncodeNode[1], new int[0]);
        ChildSlot slot = new ChildSlot(ext.storageDType(), data, 0);
        return new CascadeStep(partialRoot, List.of(), List.of(slot), null, null, true);
    }
}
