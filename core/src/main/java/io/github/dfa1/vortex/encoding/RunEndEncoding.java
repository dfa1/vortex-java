package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.DTypeProtos;
import dev.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.SegmentAllocator;
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
public final class RunEndEncoding implements Encoding {

	private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private static Array expand(
			MemorySegment endsSeg, MemorySegment valuesSeg,
			PType endsPtype, PType valuePtype,
			long numRuns, long offset, long n,
			DType dtype, SegmentAllocator arena
	) {
		MemorySegment out = arena.allocate(n * valuePtype.byteSize());
		switch (valuePtype) {
			case I8, U8 -> expandByte(endsSeg, valuesSeg, endsPtype, numRuns, offset, n, out);
			case I16, U16 -> expandShort(endsSeg, valuesSeg, endsPtype, numRuns, offset, n, out);
			case I32, U32 -> expandInt(endsSeg, valuesSeg, endsPtype, numRuns, offset, n, out);
			case I64, U64 -> expandLong(endsSeg, valuesSeg, endsPtype, numRuns, offset, n, out);
			default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "unsupported ptype " + valuePtype);
		}
		MemorySegment ro = out.asReadOnly();
		return switch (valuePtype) {
			case I64, U64 -> new LongArray(dtype, n, ro, ArrayStats.empty());
			case I32, U32 -> new IntArray(dtype, n, ro, ArrayStats.empty());
			case I16, U16 -> new ShortArray(dtype, n, ro, ArrayStats.empty());
			case I8, U8   -> new ByteArray(dtype, n, ro, ArrayStats.empty());
			default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "unsupported ptype " + valuePtype);
		};
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
			default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "non-unsigned ends ptype " + ptype);
		};
	}

	private static Array expandBool(
			Array endsArr, BoolArray valuesArr,
			PType endsPtype, long numRuns, long offset, long n,
			DType dtype, SegmentAllocator arena
	) {
		MemorySegment endsSeg = endsArr.buffer(0);
		long numBytes = (n + 7) >>> 3;
		MemorySegment out = arena.allocate(numBytes);

		long outIdx = 0;
		long logicalPos = 0;
		for (long run = 0; run < numRuns && outIdx < n; run++) {
			long runEnd = readUnsigned(endsSeg, run, endsPtype);
			boolean val = valuesArr.getBoolean(run);
			long lo = Math.max(logicalPos, offset);
			long hi = Math.min(runEnd, offset + n);
			for (long lp = lo; lp < hi; lp++, outIdx++) {
				if (val) {
					long byteIdx = outIdx >>> 3;
					byte cur = out.get(ValueLayout.JAVA_BYTE, byteIdx);
					out.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) (cur | (1 << (outIdx & 7))));
				}
			}
			logicalPos = runEnd;
		}
		return new BoolArray(dtype, n, out.asReadOnly(), ArrayStats.empty());
	}

	private static Array expandStrings(
			Array endsArr, VarBinArray valuesArr,
			PType endsPtype, long numRuns, long offset, long n,
			DType dtype, SegmentAllocator arena
	) {
		MemorySegment endsSeg = endsArr.buffer(0);
		MemorySegment valBytes = valuesArr.buffer(0);
		MemorySegment valOffsets = valuesArr.child(0).buffer(0);
		PType valOffPtype = ((DType.Primitive) valuesArr.child(0).dtype()).ptype();

		// First pass: total output byte length
		long totalBytes = 0;
		long logicalPos = 0;
		for (long run = 0; run < numRuns; run++) {
			long runEnd = readUnsigned(endsSeg, run, endsPtype);
			long lo = Math.max(logicalPos, offset);
			long hi = Math.min(runEnd, offset + n);
			long count = Math.max(0, hi - lo);
			long strLen = readVarBinOffset(valOffsets, run + 1, valOffPtype)
					- readVarBinOffset(valOffsets, run, valOffPtype);
			totalBytes += count * strLen;
			logicalPos = runEnd;
		}

		MemorySegment outBytes = arena.allocate(totalBytes > 0 ? totalBytes : 1);
		MemorySegment outOffsets = arena.allocate((n + 1) * 4L, 4);
		outOffsets.setAtIndex(LE_INT, 0, 0);

		long bytePos = 0;
		long outIdx = 0;
		logicalPos = 0;
		for (long run = 0; run < numRuns && outIdx < n; run++) {
			long runEnd = readUnsigned(endsSeg, run, endsPtype);
			long lo = Math.max(logicalPos, offset);
			long hi = Math.min(runEnd, offset + n);
			if (hi > lo) {
				long strStart = readVarBinOffset(valOffsets, run, valOffPtype);
				long strEnd = readVarBinOffset(valOffsets, run + 1, valOffPtype);
				long strLen = strEnd - strStart;
				for (long lp = lo; lp < hi; lp++, outIdx++) {
					if (strLen > 0) {
						MemorySegment.copy(valBytes, strStart, outBytes, bytePos, strLen);
						bytePos += strLen;
					}
					outOffsets.setAtIndex(LE_INT, outIdx + 1, (int) bytePos);
				}
			}
			logicalPos = runEnd;
		}

		DType i32 = new DType.Primitive(PType.I32, false);
		Array offsetArr = new IntArray(i32, n + 1, outOffsets.asReadOnly(), ArrayStats.empty());
		return new VarBinArray(dtype, n, outBytes.asReadOnly(), offsetArr, PType.I32, ArrayStats.empty());
	}

	private static long readVarBinOffset(MemorySegment seg, long i, PType ptype) {
		return switch (ptype) {
			case I32, U32 -> Integer.toUnsignedLong(seg.getAtIndex(LE_INT, i));
			case I64, U64 -> seg.getAtIndex(LE_LONG, i);
			default -> throw new VortexException(EncodingId.VORTEX_RUNEND, "unsupported offset ptype " + ptype);
		};
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private static PType ptypeFromProto(DTypeProtos.PType proto) {
		return PType.values()[proto.getNumber()];
	}

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_RUNEND;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new UnsupportedOperationException("encode not supported by " + encodingId());
	}

	@Override
	public Array decode(DecodeContext ctx) {
		ByteBuffer rawMeta = ctx.metadata();
		if (rawMeta == null) {
			throw new VortexException(EncodingId.VORTEX_RUNEND, "missing metadata");
		}

		EncodingProtos.RunEndMetadata meta;
		try {
			meta = EncodingProtos.RunEndMetadata.parseFrom(rawMeta.duplicate());
		} catch (InvalidProtocolBufferException e) {
			throw new VortexException(EncodingId.VORTEX_RUNEND, "invalid metadata", e);
		}

		PType endsPtype = ptypeFromProto(meta.getEndsPtype());
		long numRuns = meta.getNumRuns();
		long offset = meta.getOffset();

		long n = ctx.rowCount();
		DType endsDtype = new DType.Primitive(endsPtype, false);
		Array endsArr = decodeChildAs(ctx, 0, endsDtype, numRuns);

		if (ctx.dtype() instanceof DType.Utf8 || ctx.dtype() instanceof DType.Binary) {
			Array valuesArr = decodeChildAs(ctx, 1, ctx.dtype(), numRuns);
			return expandStrings(endsArr, (VarBinArray) valuesArr, endsPtype, numRuns, offset, n, ctx.dtype(), ctx.arena());
		}

		if (ctx.dtype() instanceof DType.Bool) {
			Array valuesArr = decodeChildAs(ctx, 1, ctx.dtype(), numRuns);
			return expandBool(endsArr, (BoolArray) valuesArr, endsPtype, numRuns, offset, n, ctx.dtype(), ctx.arena());
		}

		if (!(ctx.dtype() instanceof DType.Primitive p)) {
			throw new VortexException(EncodingId.VORTEX_RUNEND, "expected primitive dtype, got " + ctx.dtype());
		}
		PType valuePtype = p.ptype();
		Array valuesArr = decodeChildAs(ctx, 1, ctx.dtype(), numRuns);

		return expand(endsArr.buffer(0), valuesArr.buffer(0),
				endsPtype, valuePtype, numRuns, offset, n, ctx.dtype(), ctx.arena());
	}
}
