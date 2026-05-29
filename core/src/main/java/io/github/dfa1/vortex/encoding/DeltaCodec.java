package io.github.dfa1.vortex.encoding;

import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/// Codec for `fastlanes.delta` — delta encoding for integer columns.
///
/// Metadata (17 bytes): base_value (i64 LE) | bit_width (u8) | delta_frame_of_ref (i64 LE).
/// Stores the first value as base, then bit-packs per-element deltas (LSB-first).
/// Optimal for monotonic or near-monotonic integer sequences.
public final class DeltaCodec implements Codec {

	private static ByteBuffer pack(long[] values, long frameOfRef, int bitWidth) {
		int n = values.length;
		int totalBits = n * bitWidth;
		byte[] buf = new byte[(totalBits + 7) / 8];

		for (int i = 0; i < n; i++) {
			long shifted = values[i] - frameOfRef;
			int bitPos = i * bitWidth;
			for (int b = 0; b < bitWidth; b++) {
				if (((shifted >>> b) & 1L) == 1L) {
					buf[(bitPos + b) >>> 3] |= (byte) (1 << ((bitPos + b) & 7));
				}
			}
		}
		return ByteBuffer.wrap(buf);
	}

	private static long[] unpack(MemorySegment packed, int count, int bitWidth, long frameOfRef) {
		long[] out = new long[count];
		long size = packed.byteSize();
		for (int i = 0; i < count; i++) {
			long val = 0L;
			int bitPos = i * bitWidth;
			for (int b = 0; b < bitWidth; b++) {
				int byteIdx = (bitPos + b) >>> 3;
				int bitIdx = (bitPos + b) & 7;
				if (byteIdx < size && ((packed.get(ValueLayout.JAVA_BYTE, byteIdx) >>> bitIdx) & 1) == 1) {
					val |= 1L << b;
				}
			}
			out[i] = val + frameOfRef;
		}
		return out;
	}

	// ── Encode ────────────────────────────────────────────────────────────────

	private static long[] toLongs(Object data, PType ptype) {
		return switch (ptype) {
			case I8 -> {
				byte[] arr = (byte[]) data;
				long[] r = new long[arr.length];
				for (int i = 0; i < arr.length; i++) {
					r[i] = arr[i];
				}
				yield r;
			}
			case U8 -> {
				byte[] arr = (byte[]) data;
				long[] r = new long[arr.length];
				for (int i = 0; i < arr.length; i++) {
					r[i] = Byte.toUnsignedLong(arr[i]);
				}
				yield r;
			}
			case I16 -> {
				short[] arr = (short[]) data;
				long[] r = new long[arr.length];
				for (int i = 0; i < arr.length; i++) {
					r[i] = arr[i];
				}
				yield r;
			}
			case U16 -> {
				short[] arr = (short[]) data;
				long[] r = new long[arr.length];
				for (int i = 0; i < arr.length; i++) {
					r[i] = Short.toUnsignedLong(arr[i]);
				}
				yield r;
			}
			case I32 -> {
				int[] arr = (int[]) data;
				long[] r = new long[arr.length];
				for (int i = 0; i < arr.length; i++) {
					r[i] = arr[i];
				}
				yield r;
			}
			case U32 -> {
				int[] arr = (int[]) data;
				long[] r = new long[arr.length];
				for (int i = 0; i < arr.length; i++) {
					r[i] = Integer.toUnsignedLong(arr[i]);
				}
				yield r;
			}
			case I64, U64 -> (long[]) data;
			default -> throw new VortexException(CodecId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
		};
	}

	// ── Decode ────────────────────────────────────────────────────────────────

	private static MemorySegment fromLongs(long[] longs, PType ptype, SegmentAllocator arena) {
		// Fast path: 64-bit target — bulk byte-order copy, no per-element narrowing.
		if (ptype == PType.I64 || ptype == PType.U64) {
			MemorySegment dst = arena.allocate((long) longs.length * 8);
			MemorySegment.copy(MemorySegment.ofArray(longs), ValueLayout.JAVA_LONG, 0L,
					dst, PTypeIO.valueLayout(ptype), 0L, longs.length);
			return dst;
		}
		// Narrowing path: per-element MethodHandle setter from PTypeIO.
		int n = longs.length;
		long elemSize = ptype.byteSize();
		MemorySegment seg = arena.allocate(n * elemSize);
		for (int i = 0; i < n; i++) {
			PTypeIO.set(seg, i * elemSize, ptype, longs[i]);
		}
		return seg;
	}

	// ── Bit packing ───────────────────────────────────────────────────────────

	private static byte[] statsBytes(PType ptype, long value) {
		if (isUnsigned(ptype)) {
			return ScalarProtos.ScalarValue.newBuilder().setUint64Value(value).build().toByteArray();
		}
		return ScalarProtos.ScalarValue.newBuilder().setInt64Value(value).build().toByteArray();
	}

	private static boolean isUnsigned(PType ptype) {
		return switch (ptype) {
			case U8, U16, U32, U64 -> true;
			default -> false;
		};
	}

	// ── Type conversion ───────────────────────────────────────────────────────

	@Override
	public CodecId encodingId() {
		return CodecId.FASTLANES_DELTA;
	}

	@Override
	public boolean accepts(DType dtype) {
		if (!(dtype instanceof DType.Primitive p)) {
			return false;
		}
		return switch (p.ptype()) {
			case I8, I16, I32, I64, U8, U16, U32, U64 -> true;
			default -> false;
		};
	}

	// ── Stats helpers ─────────────────────────────────────────────────────────

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		PType ptype = ((DType.Primitive) dtype).ptype();
		long[] longs = toLongs(data, ptype);
		int n = longs.length;
		boolean unsign = isUnsigned(ptype);

		long baseValue = n > 0 ? longs[0] : 0L;
		long minVal = baseValue;
		long maxVal = baseValue;

		long[] deltas = new long[Math.max(n - 1, 0)];
		for (int i = 1; i < n; i++) {
			deltas[i - 1] = longs[i] - longs[i - 1];
			if (unsign ? Long.compareUnsigned(longs[i], minVal) < 0 : longs[i] < minVal) {
				minVal = longs[i];
			}
			if (unsign ? Long.compareUnsigned(longs[i], maxVal) > 0 : longs[i] > maxVal) {
				maxVal = longs[i];
			}
		}

		long deltaFor = 0L;
		int bitWidth = 0;
		if (deltas.length > 0) {
			long dMin = deltas[0];
			long dMax = deltas[0];
			for (long d : deltas) {
				if (d < dMin) {
					dMin = d;
				}
				if (d > dMax) {
					dMax = d;
				}
			}
			deltaFor = dMin;
			long range = dMax - dMin;
			bitWidth = (range == 0L) ? 0 : (64 - Long.numberOfLeadingZeros(range));
		}

		ByteBuffer packed = pack(deltas, deltaFor, bitWidth);

		ByteBuffer meta = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN);
		meta.putLong(baseValue);
		meta.put((byte) bitWidth);
		meta.putLong(deltaFor);
		meta.flip();

		byte[] statsMin = n > 0 ? statsBytes(ptype, minVal) : null;
		byte[] statsMax = n > 0 ? statsBytes(ptype, maxVal) : null;

		EncodeNode root = new EncodeNode(encodingId(), meta, new EncodeNode[0], new int[]{0});
		return new EncodeResult(root, List.of(packed), statsMin, statsMax);
	}

	@Override
	public Array decode(DecodeContext ctx) {
		ByteBuffer rawMeta = ctx.metadata();
		if (rawMeta == null || rawMeta.capacity() < 17) {
			throw new VortexException(CodecId.FASTLANES_DELTA, "missing or truncated metadata");
		}
		ByteBuffer meta = rawMeta.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		long baseValue = meta.getLong(0);
		int bitWidth = Byte.toUnsignedInt(meta.get(8));
		long deltaFor = meta.getLong(9);

		PType ptype = ((DType.Primitive) ctx.dtype()).ptype();
		long rowCount = ctx.rowCount();

		MemorySegment packed = ctx.buffer(0);

		long[] longs = new long[(int) rowCount];
		if (rowCount > 0) {
			longs[0] = baseValue;
			long[] deltas = unpack(packed, (int) rowCount - 1, bitWidth, deltaFor);
			for (int i = 0; i < deltas.length; i++) {
				longs[i + 1] = longs[i] + deltas[i];
			}
		}

		MemorySegment seg = fromLongs(longs, ptype, ctx.arena());
		return switch (ptype) {
			case I64, U64 -> new LongArray(ctx.dtype(), rowCount, seg, ArrayStats.empty());
			case I32, U32 -> new IntArray(ctx.dtype(), rowCount, seg, ArrayStats.empty());
			case I16, U16 -> new ShortArray(ctx.dtype(), rowCount, seg, ArrayStats.empty());
			case I8, U8   -> new ByteArray(ctx.dtype(), rowCount, seg, ArrayStats.empty());
			default -> throw new VortexException(CodecId.FASTLANES_DELTA, "unsupported ptype " + ptype);
		};
	}
}
