package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;

/// Stub for {@code vortex.masked} — not yet implemented.
public final class MaskedEncoding implements Encoding {

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_MASKED;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new VortexException(EncodingId.VORTEX_MASKED, "not yet implemented");
	}

	@Override
	public Array decode(DecodeContext ctx) {
		throw new VortexException(EncodingId.VORTEX_MASKED, "not yet implemented");
	}
}
