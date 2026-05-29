package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/// Encoding for {@code vortex.zigzag} — signed integers stored as zigzag-encoded unsigned values.
///
/// <p>No buffers, empty metadata.
/// Child slot 0: the encoded unsigned integer array (U8/U16/U32/U64, same length as parent).
/// Parent dtype is the signed counterpart (I8/I16/I32/I64).
///
/// <p>Decode: {@code signed = (unsigned >>> 1) ^ -(unsigned & 1)} applied element-wise.
public final class ZigZagEncoding implements Encoding {

	private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfInt   LE_INT   = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfLong  LE_LONG  = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_ZIGZAG;
	}

	@Override
	public boolean accepts(DType dtype) {
		if (!(dtype instanceof DType.Primitive p)) {
			return false;
		}
		PType pt = p.ptype();
		return pt == PType.I8 || pt == PType.I16 || pt == PType.I32 || pt == PType.I64;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new UnsupportedOperationException("encode not supported by " + encodingId());
	}

	private static PType toUnsigned(PType signed) {
		return switch (signed) {
			case I8  -> PType.U8;
			case I16 -> PType.U16;
			case I32 -> PType.U32;
			case I64 -> PType.U64;
			default  -> throw new VortexException(EncodingId.VORTEX_ZIGZAG, "not a signed integer: " + signed);
		};
	}

	@Override
	public Array decode(DecodeContext ctx) {
		if (!(ctx.dtype() instanceof DType.Primitive p)) {
			throw new VortexException(EncodingId.VORTEX_ZIGZAG, "expected primitive dtype, got " + ctx.dtype());
		}
		PType signed = p.ptype();
		PType unsigned = toUnsigned(signed);
		long n = ctx.rowCount();

		ArrayNode childNode = ctx.node().children()[0];
		DecodeContext childCtx = new DecodeContext(
				childNode, new DType.Primitive(unsigned, false), n,
				ctx.segmentBuffers(), ctx.registry(), ctx.arena());
		Array encoded = ctx.registry().decode(childCtx);

		MemorySegment src = encoded.buffer(0);
		MemorySegment dst = ctx.arena().allocate(n * signed.byteSize());

		return switch (signed) {
			case I8 -> {
				for (long i = 0; i < n; i++) {
					int u = Byte.toUnsignedInt(src.get(ValueLayout.JAVA_BYTE, i));
					dst.set(ValueLayout.JAVA_BYTE, i, (byte) ((u >>> 1) ^ -(u & 1)));
				}
				yield new ByteArray(ctx.dtype(), n, dst, ArrayStats.empty());
			}
			case I16 -> {
				for (long i = 0; i < n; i++) {
					int u = Short.toUnsignedInt(src.get(LE_SHORT, i * 2));
					dst.set(LE_SHORT, i * 2, (short) ((u >>> 1) ^ -(u & 1)));
				}
				yield new ShortArray(ctx.dtype(), n, dst, ArrayStats.empty());
			}
			case I32 -> {
				for (long i = 0; i < n; i++) {
					int u = src.get(LE_INT, i * 4);
					dst.set(LE_INT, i * 4, (u >>> 1) ^ -(u & 1));
				}
				yield new IntArray(ctx.dtype(), n, dst, ArrayStats.empty());
			}
			case I64 -> {
				for (long i = 0; i < n; i++) {
					long u = src.get(LE_LONG, i * 8);
					dst.set(LE_LONG, i * 8, (u >>> 1) ^ -(u & 1));
				}
				yield new LongArray(ctx.dtype(), n, dst, ArrayStats.empty());
			}
			default -> throw new VortexException(EncodingId.VORTEX_ZIGZAG, "unreachable");
		};
	}
}
