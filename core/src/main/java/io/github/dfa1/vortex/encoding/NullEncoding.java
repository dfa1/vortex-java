package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.NullArray;
import io.github.dfa1.vortex.core.DType;

/// Encoding for {@code vortex.null} — all-null arrays.
///
/// No buffers, no children, empty metadata. Decode returns a [NullArray] of the requested length.
public final class NullEncoding implements Encoding {

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_NULL;
	}

	@Override
	public boolean accepts(DType dtype) {
		return dtype instanceof DType.Null;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new UnsupportedOperationException("encode not supported by " + encodingId());
	}

	@Override
	public Array decode(DecodeContext ctx) {
		return new NullArray(ctx.dtype(), ctx.rowCount());
	}
}
