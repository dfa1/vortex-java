package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;

/// Decoder for {@code vortex.ext} — extension type transparent storage wrapper.
///
/// <p>No buffers, empty metadata. Child slot 0: the storage array decoded using
/// the extension dtype's storage dtype and same row count.
///
/// <p>Decode: unwraps the single child and returns it directly.
/// The extension type information (name, metadata) is carried by {@link DType.Extension}
/// on the parent but is not needed for decoding.
public final class ExtEncoding implements Encoding {

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_EXT;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new UnsupportedOperationException("encode not supported by " + encodingId());
	}

	@Override
	public Array decode(DecodeContext ctx) {
		if (!(ctx.dtype() instanceof DType.Extension ext)) {
			throw new VortexException(EncodingId.VORTEX_EXT, "expected extension dtype, got " + ctx.dtype());
		}
		long n = ctx.rowCount();
		ArrayNode childNode = ctx.node().children()[0];
		DecodeContext childCtx = new DecodeContext(
				childNode, ext.storageDType(), n,
				ctx.segmentBuffers(), ctx.registry(), ctx.arena());
		return ctx.registry().decode(childCtx);
	}
}
