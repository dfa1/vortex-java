package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;

/// Stub for {@code vortex.variant} — not yet implemented.
public final class VariantEncoding implements Encoding {

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_VARIANT;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new VortexException(EncodingId.VORTEX_VARIANT, "not yet implemented");
	}

	@Override
	public Array decode(DecodeContext ctx) {
		throw new VortexException(EncodingId.VORTEX_VARIANT, "not yet implemented");
	}
}
