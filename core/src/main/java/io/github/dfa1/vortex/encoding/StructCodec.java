package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.StructArray;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.ArrayList;
import java.util.List;

/// Decoder for {@code vortex.struct}.
///
/// <p>Wire format (per Rust vtable):
/// <ul>
///   <li>Buffers: 0
///   <li>Metadata: empty byte array
///   <li>Children: {@code nfields} or {@code nfields + 1}. When {@code nfields + 1},
///       {@code children[0]} is the validity (bool) array and {@code children[1..]} are fields.
///       When {@code nfields}, there is no validity child.
/// </ul>
///
/// <p>For multi-field structs the outer dtype is {@link DType.Struct}. For single-field
/// nullable scalar wrappers the outer dtype is the scalar type (e.g. {@link DType.Primitive})
/// and {@code nfields == 1}.
public final class StructCodec implements Codec {

	@Override
	public CodecId encodingId() {
		return CodecId.VORTEX_STRUCT;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new UnsupportedOperationException("encode not supported by " + encodingId());
	}

	@Override
	public Array decode(DecodeContext ctx) {
		int numChildren = ctx.node().children().length;

		if (ctx.dtype() instanceof DType.Struct structDtype) {
			// Struct array: children = [validity?, field_0, ..., field_n-1]
			int nfields = structDtype.fieldTypes().size();
			if (numChildren != nfields && numChildren != nfields + 1) {
				throw new VortexException(CodecId.VORTEX_STRUCT,
						"expected %d or %d children for struct dtype, got %d"
								.formatted(nfields, nfields + 1, numChildren));
			}
			boolean hasValidity = (numChildren == nfields + 1);
			int fieldOffset = hasValidity ? 1 : 0;

			if (nfields == 1) {
				DType fieldDtype = structDtype.fieldTypes().get(0);
				ArrayNode fieldNode = ctx.node().children()[fieldOffset];
				var fieldCtx = new DecodeContext(fieldNode, fieldDtype, ctx.rowCount(),
						ctx.segmentBuffers(), ctx.registry(), ctx.arena());
				return ctx.registry().decode(fieldCtx);
			}

			// Multi-field struct: decode each field child and return a StructArray
			List<Array> fieldArrays = new ArrayList<>(nfields);
			for (int i = 0; i < nfields; i++) {
				ArrayNode fieldNode = ctx.node().children()[fieldOffset + i];
				DType fieldDtype = structDtype.fieldTypes().get(i);
				var fieldCtx = new DecodeContext(fieldNode, fieldDtype, ctx.rowCount(),
						ctx.segmentBuffers(), ctx.registry(), ctx.arena());
				fieldArrays.add(ctx.registry().decode(fieldCtx));
			}
			return new StructArray(structDtype, ctx.rowCount(), fieldArrays);
		}

		// Scalar nullable wrapper: nfields == 1
		// children = [values] (non-nullable) or [validity, values] (nullable)
		int valuesIdx = switch (numChildren) {
			case 1 -> 0;
			case 2 -> 1;
			default -> throw new VortexException(CodecId.VORTEX_STRUCT,
					"unexpected child count " + numChildren + " for scalar wrapper");
		};

		ArrayNode valuesNode = ctx.node().children()[valuesIdx];
		var valuesCtx = new DecodeContext(
				valuesNode, ctx.dtype(), ctx.rowCount(),
				ctx.segmentBuffers(), ctx.registry(), ctx.arena());
		return ctx.registry().decode(valuesCtx);
	}
}
