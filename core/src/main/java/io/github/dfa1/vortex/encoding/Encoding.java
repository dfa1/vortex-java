package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.DType;

/// Combines encode and decode for one encoding type.
/// Register via [EncodingRegistry] — implementations are discoverable via ServiceLoader.
public interface Encoding {
	/// Returns the encoding id for this encoding.
	///
	/// @return encoding id
	EncodingId encodingId();

	/// Decodes an array node from the file using the provided context.
	///
	/// @param ctx decoding context containing buffers, dtype, row count, and child registry
	/// @return decoded array
	Array decode(DecodeContext ctx);

	/// Returns `true` if this encoding can encode the given dtype.
	///
	/// @param dtype the dtype to test
	/// @return `true` if this encoding accepts `dtype`
	boolean accepts(DType dtype);

	/// Encodes {@code data} to bytes using the provided arena for output buffer allocation.
	///
	/// @param dtype logical type of the data
	/// @param data  the data to encode (type depends on encoding; typically a primitive array)
	/// @param ctx   encoding context supplying the arena for output buffer allocation
	/// @return encode result containing the root node, buffers, and optional stats
	EncodeResult encode(DType dtype, Object data, EncodeContext ctx);

	/// Cascade-aware encode: returns a partial step with open child slots.
	/// Default wraps the terminal {@link #encode} result; override to expose children.
	///
	/// @param dtype the logical type of the data
	/// @param data  the data to encode
	/// @param ctx   encoding context supplying the arena, registry, and cascade parameters
	/// @return cascade step with optional open child slots
	default CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
		return CascadeStep.terminal(encode(dtype, data, ctx));
	}
}
