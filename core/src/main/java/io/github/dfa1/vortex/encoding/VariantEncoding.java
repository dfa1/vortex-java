package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;

/// Stub for {@code vortex.variant} — not yet implemented.
public final class VariantEncoding implements Encoding {

    /// Creates a new {@code VariantEncoding} instance; use via {@link EncodingRegistry}.
    public VariantEncoding() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_VARIANT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return false;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        throw new VortexException(EncodingId.VORTEX_VARIANT, "not yet implemented");
    }

    @Override
    public Array decode(DecodeContext ctx) {
        throw new VortexException(EncodingId.VORTEX_VARIANT, "not yet implemented");
    }
}
