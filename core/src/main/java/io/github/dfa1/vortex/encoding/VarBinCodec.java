package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Decoder for {@code vortex.varbin} — variable-length binary / UTF-8 string arrays.
///
/// <p>Metadata: protobuf {@code VarBinMetadata} — {@code offsets_ptype PType} (tag 1).
///
/// <p>Buffer 0: concatenated raw bytes of all strings.
/// Child slot 0: offsets array (length = rowCount + 1, dtype = offsets_ptype).
///   {@code offsets[i]..offsets[i+1]} is the byte range of element {@code i} in the bytes buffer.
/// Child slot 1 (optional): validity array — ignored for non-nullable columns.
///
/// <p>The returned {@code Array} exposes:
/// <ul>
///   <li>{@code buffer(0)} — the raw bytes segment</li>
///   <li>{@code child(0)} — the offsets array (I32 or I64 primitives)</li>
/// </ul>
public final class VarBinCodec implements Codec {

	@Override
	public CodecId encodingId() {
		return CodecId.VORTEX_VARBIN;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new UnsupportedOperationException("encode not supported by " + encodingId());
	}

	@Override
	public Array decode(DecodeContext ctx) {
		ByteBuffer rawMeta = ctx.metadata();
		if (rawMeta == null) {
			throw new VortexException(CodecId.VORTEX_VARBIN, "missing metadata");
		}
		EncodingProtos.VarBinMetadata meta;
		try {
			meta = EncodingProtos.VarBinMetadata.parseFrom(rawMeta.duplicate());
		} catch (InvalidProtocolBufferException e) {
			throw new VortexException(CodecId.VORTEX_VARBIN, "invalid metadata", e);
		}

		PType offsetsPtype = PType.values()[meta.getOffsetsPtype().getNumber()];
		DType offsetsDtype = new DType.Primitive(offsetsPtype, false);
		long n = ctx.rowCount();

		// Offsets: n+1 elements; bytes: raw string data.
		ArrayNode offsetsNode = ctx.node().children()[0];
		DecodeContext offsetsCtx = new DecodeContext(
				offsetsNode, offsetsDtype, n + 1,
				ctx.segmentBuffers(), ctx.registry(), ctx.arena());
		Array offsets = ctx.registry().decode(offsetsCtx);

		MemorySegment bytes = ctx.buffer(0);

		return new VarBinArray(ctx.dtype(), n, bytes, offsets, offsetsPtype, ArrayStats.empty());
	}
}
