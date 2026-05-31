package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

/// Stub for `vortex.pco` (PCodec numerical compression).
///
/// Not implemented: the PEncoding wire format requires a port of the ANS decoder,
/// delta predictions, and bin tokenization from the upstream `pco` Rust crate
/// ([pcodec](https://github.com/mwlon/pcodec)). No mainstream Java port exists.
///
/// Registered so files using this encoding fail with a clear attributed error
/// instead of "no encoding for encoding". Replace this stub once a Java port lands.
public final class PcoEncoding implements Encoding {

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_PCO;
	}

	@Override
	public Array decode(DecodeContext ctx) {
		throw new VortexException(EncodingId.VORTEX_PCO,
				"decode not implemented — PEncoding algorithm port pending");
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new VortexException(EncodingId.VORTEX_PCO,
				"encode not implemented — PEncoding algorithm port pending");
	}
}
