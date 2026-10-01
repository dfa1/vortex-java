package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;

import java.util.Set;
import java.util.List;

/// Write-only encoder for `vortex.ext` — wraps a storage-array encode in an ext node.
public final class ExtEncodingEncoder implements EncodingEncoder {

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
        // The storage encode already computed the column's bounds; dropping them left every
        // extension column (timestamps, dates) with no zone map at all. The reader resolves a
        // MIN/MAX stat for an extension column against its storage dtype, mirroring Rust's
        // stats_table_dtype fallback, so storage-space bounds are exactly what it expects.
        return new EncodeResult(root, childResult.encodedBuffers(), childResult.statsMin(), childResult.statsMax());
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
        ChildSlot slot = new ChildSlot(ext.storageDType(), data, 0, Set.of(EncodingId.VORTEX_EXT));
        // The cascade reads stats off the step, not off the children it later resolves, so the
        // bounds have to be computed here rather than inherited from the storage slot.
        byte[][] stats = storageStats(ext.storageDType(), data);
        return new CascadeStep(partialRoot, List.of(), List.of(slot),
                PrimitiveEncodingEncoder.minOf(stats), PrimitiveEncodingEncoder.maxOf(stats), true);
    }

    /// Zone-map min/max for an extension column, in storage space. Only a fixed-width primitive
    /// storage dtype has bounds worth recording; anything else (a fixed-size-list storage, say)
    /// reports none rather than a meaningless comparison.
    ///
    /// @param storage the extension's storage dtype
    /// @param data    the storage-shaped values being encoded
    /// @return a two-element `{min, max}` array of encoded scalars, or `null`
    private static byte[][] storageStats(DType storage, Object data) {
        return storage instanceof DType.Primitive(PType ptype, boolean ignored)
                ? PrimitiveEncodingEncoder.minMaxStats(ptype, data)
                : null;
    }
}
