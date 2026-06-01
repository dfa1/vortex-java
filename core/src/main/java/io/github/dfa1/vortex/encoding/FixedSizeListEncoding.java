package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.FixedSizeListArray;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Encoder/decoder for {@code vortex.fixed_size_list}.
///
/// <p>Wire format (per Rust vtable):
/// <ul>
///   <li>Buffers: 0
///   <li>Metadata: empty byte array
///   <li>Children: 1 or 2.
///       {@code children[0]} is the flat elements array (len = outerLen * fixedSize, dtype = elementType).
///       {@code children[1]}, when present, is the validity (bool) array (len = outerLen).
/// </ul>
///
/// <p>The {@code fixedSize} is read directly from the {@link DType.FixedSizeList} dtype —
/// it is not stored in metadata.
public final class FixedSizeListEncoding implements Encoding {

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_FIXED_SIZE_LIST;
	}

	@Override
	public boolean accepts(DType dtype) {
		return dtype instanceof DType.FixedSizeList;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		return Encoder.encode((DType.FixedSizeList) dtype, (FixedSizeListData) data);
	}

	@Override
	public Array decode(DecodeContext ctx) {
		return Decoder.decode(ctx);
	}

	private static final class Encoder {

		private static final List<Encoding> FALLBACK = List.of(
				new PrimitiveEncoding(), new VarBinEncoding(), new BoolEncoding(),
				new NullEncoding(), new ByteBoolEncoding());

		static EncodeResult encode(DType.FixedSizeList dtype, FixedSizeListData data) {
			DType elementType = dtype.elementType();
			Encoding inner = findEncoding(elementType);

			EncodeResult elemResult = inner.encode(elementType, data.elements());

			List<MemorySegment> allBuffers = new ArrayList<>(elemResult.buffers());
			EncodeNode elemNode = EncodeNode.remapBufferIndices(elemResult.rootNode(), 0);

			EncodeNode root = new EncodeNode(
					EncodingId.VORTEX_FIXED_SIZE_LIST,
					ByteBuffer.wrap(new byte[0]),
					new EncodeNode[]{elemNode},
					new int[]{});
			return new EncodeResult(root, List.copyOf(allBuffers), null, null);
		}

		private static Encoding findEncoding(DType dtype) {
			for (Encoding enc : FALLBACK) {
				if (enc.accepts(dtype)) {
					return enc;
				}
			}
			throw new UnsupportedOperationException("no fallback encoding for dtype: " + dtype);
		}
	}

	private static final class Decoder {

		static Array decode(DecodeContext ctx) {
			if (!(ctx.dtype() instanceof DType.FixedSizeList fsl)) {
				throw new VortexException(EncodingId.VORTEX_FIXED_SIZE_LIST,
						"expected DType.FixedSizeList, got " + ctx.dtype());
			}

			int nchildren = ctx.node().children().length;
			if (nchildren < 1 || nchildren > 2) {
				throw new VortexException(EncodingId.VORTEX_FIXED_SIZE_LIST,
						"expected 1 or 2 children, got " + nchildren);
			}

			long outerLen = ctx.rowCount();
			long elemLen = outerLen * fsl.fixedSize();
			DType elementType = fsl.elementType();

			ArrayNode elemNode = ctx.node().children()[0];
			var elemCtx = new DecodeContext(
					elemNode, elementType, elemLen,
					ctx.segmentBuffers(), ctx.registry(), ctx.arena());
			Array elements = ctx.registry().decode(elemCtx);

			return new FixedSizeListArray(fsl, outerLen, elements);
		}
	}
}
