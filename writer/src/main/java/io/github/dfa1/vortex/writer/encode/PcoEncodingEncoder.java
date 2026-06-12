package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodeContext;
import io.github.dfa1.vortex.encoding.EncodeResult;
import io.github.dfa1.vortex.encoding.EncodingEncoder;
import io.github.dfa1.vortex.encoding.EncodingId;

/// Write-side stub for {@code vortex.pco} — encode is not yet implemented.
public final class PcoEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public PcoEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PCO;
    }

    @Override
    public boolean accepts(DType dtype) {
        return false;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        throw new VortexException(EncodingId.VORTEX_PCO,
                "encode not implemented — pco encode port pending");
    }
}
