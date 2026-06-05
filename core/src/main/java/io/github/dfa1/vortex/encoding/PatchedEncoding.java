package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;

/// Stub for {@code vortex.patched} — not yet implemented.
public final class PatchedEncoding implements Encoding {

	/// Creates a new {@code PatchedEncoding} instance; use via {@link EncodingRegistry}.
	public PatchedEncoding() {
	}

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_PATCHED;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new VortexException(EncodingId.VORTEX_PATCHED, "not yet implemented");
	}

	@Override
	public Array decode(DecodeContext ctx) {
		throw new VortexException(EncodingId.VORTEX_PATCHED, "not yet implemented");
	}
}
