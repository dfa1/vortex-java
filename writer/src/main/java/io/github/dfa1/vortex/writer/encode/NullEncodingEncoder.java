package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;

import java.util.List;

/// Write-only encoder for `vortex.null` (all-null arrays).
public final class NullEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public NullEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_NULL;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Null;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_NULL, null, new EncodeNode[0], new int[0]);
        return new EncodeResult(root, List.of(), null, null);
    }
}
