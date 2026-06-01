package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.GenericArray;
import io.github.dfa1.vortex.proto.EncodingProtos;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Decoder for {@code vortex.datetimeparts} — timestamp split into days/seconds/subseconds.
///
/// <p>Wire format (per Rust vtable):
/// <ul>
///   <li>Metadata: {@code DateTimePartsMetadata} — three PType fields (tag 1/2/3):
///       {@code days_ptype}, {@code seconds_ptype}, {@code subseconds_ptype}
///   <li>Buffers: 0
///   <li>Children: 3
///       <ul>
///         <li>Slot 0 — {@code days}: {@code Primitive(days_ptype, parentNullability)}
///         <li>Slot 1 — {@code seconds}: {@code Primitive(seconds_ptype, false)}
///         <li>Slot 2 — {@code subseconds}: {@code Primitive(subseconds_ptype, false)}
///       </ul>
/// </ul>
public final class DateTimePartsEncoding implements Encoding {

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_DATETIMEPARTS;
	}

	@Override
	public boolean accepts(DType dtype) {
		return dtype instanceof DType.Extension;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new UnsupportedOperationException("encode not yet implemented for " + encodingId());
	}

	@Override
	public Array decode(DecodeContext ctx) {
		return Decoder.decode(ctx);
	}

	private static final class Decoder {

		private static Array decode(DecodeContext ctx) {
			ByteBuffer meta = ctx.metadata();
			if (meta == null || meta.remaining() == 0) {
				throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS, "missing metadata");
			}
			EncodingProtos.DateTimePartsMetadata decoded;
			try {
				byte[] bytes = new byte[meta.remaining()];
				meta.duplicate().get(bytes);
				decoded = EncodingProtos.DateTimePartsMetadata.parseFrom(bytes);
			} catch (InvalidProtocolBufferException e) {
				throw new VortexException(EncodingId.VORTEX_DATETIMEPARTS, "invalid metadata: " + e.getMessage());
			}

			PType daysPtype = PType.values()[decoded.getDaysPtypeValue()];
			PType secondsPtype = PType.values()[decoded.getSecondsPtypeValue()];
			PType subsecondsPtype = PType.values()[decoded.getSubsecondsPtypeValue()];
			boolean nullable = ctx.dtype().nullable();

			Array days = decodeChild(ctx, 0, new DType.Primitive(daysPtype, nullable));
			Array seconds = decodeChild(ctx, 1, new DType.Primitive(secondsPtype, false));
			Array subseconds = decodeChild(ctx, 2, new DType.Primitive(subsecondsPtype, false));

			return new GenericArray(ctx.dtype(), ctx.rowCount(), new MemorySegment[0],
					new Array[]{days, seconds, subseconds});
		}

		private static Array decodeChild(DecodeContext ctx, int idx, DType childDtype) {
			ArrayNode childNode = ctx.node().children()[idx];
			DecodeContext childCtx = new DecodeContext(
					childNode, childDtype, ctx.rowCount(),
					ctx.segmentBuffers(), ctx.registry(), ctx.arena());
			return ctx.registry().decode(childCtx);
		}
	}
}
