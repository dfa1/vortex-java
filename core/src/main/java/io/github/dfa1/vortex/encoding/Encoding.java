package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.DType;

/// Combines encode and decode for one encoding type.
/// Register via [EncodingRegistry] — implementations are discoverable via ServiceLoader.
public interface Encoding {
	/// Return the encoding id for this encoding.
	EncodingId encodingId();

	/// Decode an array node from the file using the provided context.
	Array decode(DecodeContext ctx);

	/// Returns true if this encoding can encode the given dtype.
	default boolean accepts(DType dtype) {
		return false;
	}

	/// Encodes `data` to bytes, including per-chunk min/max stats when available.
	EncodeResult encode(DType dtype, Object data);

	/// Cascade-aware encode: returns a partial step with open child slots.
	/// Default wraps the terminal {@link #encode} result; override to expose children.
	default CascadeStep encodeCascade(DType dtype, Object data, CompressorContext ctx) {
		return CascadeStep.terminal(encode(dtype, data));
	}
}
