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
import java.util.List;

/// Decoder for {@code vortex.decimal_byte_parts} — decimal split into MSP + LSP children.
///
/// <p>Wire format (per Rust vtable):
/// <ul>
///   <li>Metadata: {@code DecimalBytePartsMetadata} — {@code zeroth_child_ptype PType} (tag 1),
///       {@code lower_part_count uint32} (tag 2, always 0 in current Rust impl)
///   <li>Buffers: 0
///   <li>Children: 1 — {@code msp} (most-significant part), a signed primitive array carrying
///       the array's nullability; dtype is {@code Primitive(zeroth_child_ptype, parentNullability)}
/// </ul>
public final class DecimalBytePartsEncoding implements Encoding {

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_DECIMAL_BYTE_PARTS;
	}

	@Override
	public boolean accepts(DType dtype) {
		return dtype instanceof DType.Decimal;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		return Encoder.encode((DType.Decimal) dtype, (long[]) data);
	}

	@Override
	public Array decode(DecodeContext ctx) {
		return Decoder.decode(ctx);
	}

	private static final class Encoder {

		static EncodeResult encode(DType.Decimal dtype, long[] data) {
			DType mspDtype = new DType.Primitive(PType.I64, dtype.nullable());
			EncodeResult mspResult = new PrimitiveEncoding().encode(mspDtype, data);

			EncodingProtos.DecimalBytePartsMetadata proto = EncodingProtos.DecimalBytePartsMetadata.newBuilder()
					.setZerothChildPtype(
							io.github.dfa1.vortex.proto.DTypeProtos.PType.forNumber(PType.I64.ordinal()))
					.setLowerPartCount(0)
					.build();
			ByteBuffer metaBuf = ByteBuffer.wrap(proto.toByteArray());

			EncodeNode mspNode = EncodeNode.remapBufferIndices(mspResult.rootNode(), 0);
			EncodeNode root = new EncodeNode(
					EncodingId.VORTEX_DECIMAL_BYTE_PARTS, metaBuf, new EncodeNode[]{mspNode}, new int[]{});
			return new EncodeResult(root, List.copyOf(mspResult.buffers()), null, null);
		}
	}

	private static final class Decoder {

		private static Array decode(DecodeContext ctx) {
			ByteBuffer meta = ctx.metadata();
			if (meta == null || meta.remaining() == 0) {
				throw new VortexException(EncodingId.VORTEX_DECIMAL_BYTE_PARTS, "missing metadata");
			}
			EncodingProtos.DecimalBytePartsMetadata decoded;
			try {
				byte[] bytes = new byte[meta.remaining()];
				meta.duplicate().get(bytes);
				decoded = EncodingProtos.DecimalBytePartsMetadata.parseFrom(bytes);
			} catch (InvalidProtocolBufferException e) {
				throw new VortexException(EncodingId.VORTEX_DECIMAL_BYTE_PARTS, "invalid metadata: " + e.getMessage());
			}

			int lowerPartCount = decoded.getLowerPartCount();
			if (lowerPartCount != 0) {
				throw new VortexException(EncodingId.VORTEX_DECIMAL_BYTE_PARTS,
						"lower_part_count > 0 not supported, got " + lowerPartCount);
			}

			PType mspPtype = PType.values()[decoded.getZerothChildPtypeValue()];
			boolean nullable = ctx.dtype().nullable();
			DType mspDtype = new DType.Primitive(mspPtype, nullable);
			ArrayNode mspNode = ctx.node().children()[0];
			DecodeContext mspCtx = new DecodeContext(
					mspNode, mspDtype, ctx.rowCount(),
					ctx.segmentBuffers(), ctx.registry(), ctx.arena());
			Array mspArray = ctx.registry().decode(mspCtx);
			return new GenericArray(ctx.dtype(), ctx.rowCount(), new MemorySegment[0],
					new Array[]{mspArray});
		}
	}
}
