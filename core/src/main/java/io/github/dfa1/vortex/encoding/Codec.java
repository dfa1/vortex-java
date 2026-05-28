package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.DType;

/// Combines encode and decode for one encoding type.
/// Register via [CodecRegistry] — implementations are discoverable via ServiceLoader.
public interface Codec {
    /// Return the encoding id for this codec.
    EncodingId encodingId();

    /// Decode an array node from the file using the provided context.
    Array decode(DecodeContext ctx);

    /// Returns true if this codec can encode the given dtype.
    default boolean accepts(DType dtype) { return false; }

    /// Encodes `data` to bytes, including per-chunk min/max stats when available.
    default EncodeResult encode(DType dtype, Object data) {
        throw new UnsupportedOperationException("encode not supported by this codec");
    }
}
