package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.NullArray;

import java.util.List;

/// Encoding for {@code vortex.null} — all-null arrays.
///
/// No buffers, no children, empty metadata. Decode returns a [NullArray] of the requested length.
public final class NullEncoding implements Encoding {

    /// Creates a new {@code NullEncoding} instance; use via {@link EncodingRegistry}.
    public NullEncoding() {
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

    @Override
    public Array decode(DecodeContext ctx) {
        return new NullArray(ctx.dtype(), ctx.rowCount());
    }
}
