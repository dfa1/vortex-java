package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.MaskedArray;

/// Decoder for {@code vortex.masked}.
///
/// <p>Wire format:
/// <ul>
///   <li>Metadata: empty.</li>
///   <li>Buffers: none.</li>
///   <li>Child 0: payload array encoded with non-nullable dtype (no actual nulls by invariant).</li>
///   <li>Child 1 (optional): validity bitmap, dtype {@code Bool(false)}. Absent means AllValid.</li>
/// </ul>
public final class MaskedEncoding implements Encoding {

	/// Creates a new {@code MaskedEncoding} instance; use via {@link EncodingRegistry}.
	public MaskedEncoding() {
	}

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_MASKED;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
		throw new VortexException(EncodingId.VORTEX_MASKED, "encode not yet implemented");
	}

	@Override
	public Array decode(DecodeContext ctx) {
		return Decoder.decode(ctx);
	}

	private static final class Decoder {

		static Array decode(DecodeContext ctx) {
			if (ctx.node().bufferIndices().length != 0) {
				throw new VortexException(EncodingId.VORTEX_MASKED,
						"expected 0 buffers, got " + ctx.node().bufferIndices().length);
			}
			int numChildren = ctx.node().children().length;
			if (numChildren < 1 || numChildren > 2) {
				throw new VortexException(EncodingId.VORTEX_MASKED,
						"expected 1 or 2 children, got " + numChildren);
			}

			Array child = decodeChild(ctx, 0, ctx.dtype().withNullable(false), ctx.rowCount());

			BoolArray validity = null;
			if (numChildren == 2) {
				Array validityArray = decodeChild(ctx, 1, new DType.Bool(false), ctx.rowCount());
				if (!(validityArray instanceof BoolArray ba)) {
					throw new VortexException(EncodingId.VORTEX_MASKED,
							"validity child decoded to unexpected type: " + validityArray.getClass().getSimpleName());
				}
				validity = ba;
			}

			return new MaskedArray(child, validity);
		}

		private static Array decodeChild(DecodeContext parent, int idx, DType dtype, long rowCount) {
			ArrayNode childNode = parent.node().children()[idx];
			DecodeContext childCtx = new DecodeContext(
					childNode, dtype, rowCount,
					parent.segmentBuffers(), parent.registry(), parent.arena());
			return parent.registry().decode(childCtx);
		}
	}
}
