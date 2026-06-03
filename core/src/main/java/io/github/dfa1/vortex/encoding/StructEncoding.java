package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.StructArray;

import java.lang.foreign.MemorySegment;
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
public final class StructEncoding implements Encoding {

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_STRUCT;
	}

	@Override
	public boolean accepts(DType dtype) {
		return dtype instanceof DType.Struct;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		return Encoder.encode((DType.Struct) dtype, (StructData) data);
	}

	@Override
	public Array decode(DecodeContext ctx) {
		return Decoder.decode(ctx);
	}

	private static final class Encoder {

		private static final List<Encoding> FALLBACK = List.of(
				new PrimitiveEncoding(), new VarBinEncoding(), new BoolEncoding(),
				new NullEncoding(), new ByteBoolEncoding());

		static EncodeResult encode(DType.Struct dtype, StructData data) {
			List<Object> fields = data.fieldArrays();
			List<DType> fieldTypes = dtype.fieldTypes();
			if (fields.size() != fieldTypes.size()) {
				throw new VortexException(EncodingId.VORTEX_STRUCT,
						"fieldArrays length %d != fieldTypes length %d".formatted(fields.size(), fieldTypes.size()));
			}
			List<MemorySegment> allBuffers = new ArrayList<>();
			EncodeNode[] children = new EncodeNode[fields.size()];
			for (int i = 0; i < fields.size(); i++) {
				DType fieldDtype = fieldTypes.get(i);
				EncodeResult fieldResult = findEncoding(fieldDtype).encode(fieldDtype, fields.get(i));
				int bufOffset = allBuffers.size();
				children[i] = EncodeNode.remapBufferIndices(fieldResult.rootNode(), bufOffset);
				allBuffers.addAll(fieldResult.buffers());
			}
			EncodeNode root = new EncodeNode(EncodingId.VORTEX_STRUCT, null, children, new int[0]);
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

		private static Array decode(DecodeContext ctx) {
			int numChildren = ctx.node().children().length;

			if (ctx.dtype() instanceof DType.Struct structDtype) {
				// Struct array: children = [validity?, field_0, ..., field_n-1]
				int nfields = structDtype.fieldTypes().size();
				if (numChildren != nfields && numChildren != nfields + 1) {
					throw new VortexException(EncodingId.VORTEX_STRUCT,
							"expected %d or %d children for struct dtype, got %d"
									.formatted(nfields, nfields + 1, numChildren));
				}
				boolean hasValidity = (numChildren == nfields + 1);
				int fieldOffset = hasValidity ? 1 : 0;

				BoolArray structValidity = null;
				if (hasValidity) {
					ArrayNode validityNode = ctx.node().children()[0];
					var validityCtx = new DecodeContext(validityNode, new DType.Bool(false),
							ctx.rowCount(), ctx.segmentBuffers(), ctx.registry(), ctx.arena());
					Array va = ctx.registry().decode(validityCtx);
					if (!(va instanceof BoolArray ba)) {
						throw new VortexException(EncodingId.VORTEX_STRUCT,
								"struct validity decoded to unexpected type: " + va.getClass().getSimpleName());
					}
					structValidity = ba;
				}

				if (nfields == 1) {
					DType fieldDtype = structDtype.fieldTypes().getFirst();
					ArrayNode fieldNode = ctx.node().children()[fieldOffset];
					var fieldCtx = new DecodeContext(fieldNode, fieldDtype.withNullable(false),
							ctx.rowCount(), ctx.segmentBuffers(), ctx.registry(), ctx.arena());
					Array field = ctx.registry().decode(fieldCtx);
					return structValidity != null ? new MaskedArray(field, structValidity) : field;
				}

				List<Array> fieldArrays = new ArrayList<>(nfields);
				for (int i = 0; i < nfields; i++) {
					ArrayNode fieldNode = ctx.node().children()[fieldOffset + i];
					DType fieldDtype = structDtype.fieldTypes().get(i);
					var fieldCtx = new DecodeContext(fieldNode, fieldDtype.withNullable(false),
							ctx.rowCount(), ctx.segmentBuffers(), ctx.registry(), ctx.arena());
					Array field = ctx.registry().decode(fieldCtx);
					fieldArrays.add(structValidity != null ? new MaskedArray(field, structValidity) : field);
				}
				return new StructArray(structDtype, ctx.rowCount(), fieldArrays);
			}

			// Scalar nullable wrapper: nfields == 1
			// children = [values] (non-nullable) or [validity, values] (nullable)
			if (numChildren == 1) {
				ArrayNode valuesNode = ctx.node().children()[0];
				var valuesCtx = new DecodeContext(
						valuesNode, ctx.dtype(), ctx.rowCount(),
						ctx.segmentBuffers(), ctx.registry(), ctx.arena());
				return ctx.registry().decode(valuesCtx);
			} else if (numChildren == 2) {
				ArrayNode validityNode = ctx.node().children()[0];
				var validityCtx = new DecodeContext(validityNode, new DType.Bool(false),
						ctx.rowCount(), ctx.segmentBuffers(), ctx.registry(), ctx.arena());
				Array va = ctx.registry().decode(validityCtx);
				if (!(va instanceof BoolArray validity)) {
					throw new VortexException(EncodingId.VORTEX_STRUCT,
							"scalar wrapper validity decoded to unexpected type: " + va.getClass().getSimpleName());
				}
				ArrayNode valuesNode = ctx.node().children()[1];
				var valuesCtx = new DecodeContext(
						valuesNode, ctx.dtype().withNullable(false), ctx.rowCount(),
						ctx.segmentBuffers(), ctx.registry(), ctx.arena());
				Array values = ctx.registry().decode(valuesCtx);
				return new MaskedArray(values, validity);
			} else {
				throw new VortexException(EncodingId.VORTEX_STRUCT,
						"unexpected child count " + numChildren + " for scalar wrapper");
			}
		}
	}
}
