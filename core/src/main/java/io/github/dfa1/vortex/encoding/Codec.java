package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;

/// Combines encode and decode for one encoding type.
/// Register via [DecoderRegistry] — [Codec] is a [Decoder], so `registry.register(codec)` works.
public interface Codec extends Decoder {
    /// Returns true if this codec can encode the given dtype.
    boolean accepts(DType dtype);

    /// Encodes `data` to bytes, including per-chunk min/max stats when available.
    EncodeResult encode(DType dtype, Object data);
}
