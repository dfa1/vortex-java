package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.DTypeProtos;
import dev.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Decoder for {@code vortex.runend} — run-end (RLE) encoding.
///
/// <p>Metadata: protobuf {@code RunEndMetadata} — {@code ends_ptype PType} (tag 1),
/// {@code num_runs u64} (tag 2), {@code offset u64} (tag 3).
///
/// <p>Child slot 0: run-end positions (unsigned, cumulative exclusive ends).
/// Child slot 1: run values (same dtype as parent, one per run).
///
/// <p>Decode: for each run i, repeat {@code values[i]} for positions
/// {@code [ends[i-1], ends[i])} in the output, skipping the first {@code offset} logical elements.
public final class RunEndCodec implements Codec {

	private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private static Array expand(
			MemorySegment endsSeg, MemorySegment valuesSeg,
			PType endsPtype, PType valuePtype,
			long numRuns, long offset, long n,
			DType dtype, Arena arena
	) {
		MemorySegment out = arena.allocate(n * valuePtype.byteSize());
		switch (valuePtype) {
			case I8, U8 -> expandByte(endsSeg, valuesSeg, endsPtype, numRuns, offset, n, out);
			case I16, U16 -> expandShort(endsSeg, valuesSeg, endsPtype, numRuns, offset, n, out);
			case I32, U32 -> expandInt(endsSeg, valuesSeg, endsPtype, numRuns, offset, n, out);
			case I64, U64 -> expandLong(endsSeg, valuesSeg, endsPtype, numRuns, offset, n, out);
			default -> throw new UnsupportedOperationException("vortex.runend: unsupported ptype " + valuePtype);
		}
		return new Array(dtype, n, new MemorySegment[]{out.asReadOnly()}, Array.NO_CHILDREN, ArrayStats.empty());
	}

	private static void expandByte(MemorySegment endsSeg, MemorySegment valuesSeg,
	                               PType endsPtype, long numRuns, long offset, long n, MemorySegment out) {
		long logicalPos = 0L, outPos = 0L;
		for (long run = 0; run < numRuns && outPos < n; run++) {
			long runEnd = readUnsigned(endsSeg, run, endsPtype);
			byte rawValue = valuesSeg.get(ValueLayout.JAVA_BYTE, run);
			long writeEnd = Math.min(runEnd, offset + n);
			for (long lp = Math.max(logicalPos, offset); lp < writeEnd; lp++, outPos++) {
				out.set(ValueLayout.JAVA_BYTE, outPos, rawValue);
			}
			logicalPos = runEnd;
		}
	}

	// ── Expansion ─────────────────────────────────────────────────────────────

	private static void expandShort(MemorySegment endsSeg, MemorySegment valuesSeg,
	                                PType endsPtype, long numRuns, long offset, long n, MemorySegment out) {
		long logicalPos = 0L, outPos = 0L;
		for (long run = 0; run < numRuns && outPos < n; run++) {
			long runEnd = readUnsigned(endsSeg, run, endsPtype);
			short rawValue = valuesSeg.get(LE_SHORT, run * 2);
			long writeEnd = Math.min(runEnd, offset + n);
			for (long lp = Math.max(logicalPos, offset); lp < writeEnd; lp++, outPos++) {
				out.set(LE_SHORT, outPos * 2, rawValue);
			}
			logicalPos = runEnd;
		}
	}

	private static void expandInt(MemorySegment endsSeg, MemorySegment valuesSeg,
	                              PType endsPtype, long numRuns, long offset, long n, MemorySegment out) {
		long logicalPos = 0L, outPos = 0L;
		for (long run = 0; run < numRuns && outPos < n; run++) {
			long runEnd = readUnsigned(endsSeg, run, endsPtype);
			int rawValue = valuesSeg.get(LE_INT, run * 4);
			long writeEnd = Math.min(runEnd, offset + n);
			for (long lp = Math.max(logicalPos, offset); lp < writeEnd; lp++, outPos++) {
				out.set(LE_INT, outPos * 4, rawValue);
			}
			logicalPos = runEnd;
		}
	}

	private static void expandLong(MemorySegment endsSeg, MemorySegment valuesSeg,
	                               PType endsPtype, long numRuns, long offset, long n, MemorySegment out) {
		long logicalPos = 0L, outPos = 0L;
		for (long run = 0; run < numRuns && outPos < n; run++) {
			long runEnd = readUnsigned(endsSeg, run, endsPtype);
			long rawValue = valuesSeg.get(LE_LONG, run * 8);
			long writeEnd = Math.min(runEnd, offset + n);
			for (long lp = Math.max(logicalPos, offset); lp < writeEnd; lp++, outPos++) {
				out.set(LE_LONG, outPos * 8, rawValue);
			}
			logicalPos = runEnd;
		}
	}

	private static Array decodeChildAs(DecodeContext parent, int childIdx, DType dtype, long rowCount) {
		ArrayNode childNode = parent.node().children()[childIdx];
		DecodeContext childCtx = new DecodeContext(
				childNode, dtype, rowCount, parent.segmentBuffers(), parent.registry(), parent.arena());
		return parent.registry().decode(childCtx);
	}

	private static long readUnsigned(MemorySegment seg, long i, PType ptype) {
		return switch (ptype) {
			case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
			case U16 -> Short.toUnsignedLong(seg.get(LE_SHORT, i * 2));
			case U32 -> Integer.toUnsignedLong(seg.get(LE_INT, i * 4));
			case U64 -> seg.get(LE_LONG, i * 8);
			default -> throw new IllegalStateException("vortex.runend: non-unsigned ends ptype " + ptype);
		};
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private static PType ptypeFromProto(DTypeProtos.PType proto) {
		return PType.values()[proto.getNumber()];
	}

	@Override
	public CodecId encodingId() {
		return CodecId.VORTEX_RUNEND;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new UnsupportedOperationException("encode not supported by " + encodingId());
	}

	@Override
	public Array decode(DecodeContext ctx) {
		ByteBuffer rawMeta = ctx.metadata();
		if (rawMeta == null) {
			throw new IllegalStateException("vortex.runend: missing metadata");
		}

		EncodingProtos.RunEndMetadata meta;
		try {
			meta = EncodingProtos.RunEndMetadata.parseFrom(rawMeta.duplicate());
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalStateException("vortex.runend: invalid metadata", e);
		}

		PType endsPtype = ptypeFromProto(meta.getEndsPtype());
		long numRuns = meta.getNumRuns();
		long offset = meta.getOffset();

		if (!(ctx.dtype() instanceof DType.Primitive p)) {
			throw new IllegalStateException("vortex.runend: expected primitive dtype, got " + ctx.dtype());
		}
		PType valuePtype = p.ptype();
		long n = ctx.rowCount();

		DType endsDtype = new DType.Primitive(endsPtype, false);
		Array endsArr = decodeChildAs(ctx, 0, endsDtype, numRuns);
		Array valuesArr = decodeChildAs(ctx, 1, ctx.dtype(), numRuns);

		return expand(endsArr.buffer(0), valuesArr.buffer(0),
				endsPtype, valuePtype, numRuns, offset, n, ctx.dtype(), ctx.arena());
	}
}
