package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;

/// Write-only encoder for `vortex.variant` — currently throws (not implemented).
public final class VariantEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public VariantEncodingEncoder() {
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
        throw new VortexException(EncodingId.VORTEX_VARIANT, "encode not yet implemented");
    }
}
