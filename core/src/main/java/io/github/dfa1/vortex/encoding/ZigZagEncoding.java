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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/// Encoding for {@code vortex.zigzag} — signed integers stored as zigzag-encoded unsigned values.
///
/// <p>No buffers, empty metadata.
/// Child slot 0: the encoded unsigned integer array (U8/U16/U32/U64, same length as parent).
/// Parent dtype is the signed counterpart (I8/I16/I32/I64).
///
/// <p>Decode: {@code signed = (unsigned >>> 1) ^ -(unsigned & 1)} applied element-wise.
public final class ZigZagEncoding implements Encoding {

	/// Creates a new {@code ZigZagEncoding} instance; use via {@link EncodingRegistry}.
	public ZigZagEncoding() {
	}

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
		return Encoder.encode(dtype, data);
	}

	@Override
	public Array decode(DecodeContext ctx) {
		return Decoder.decode(ctx);
	}

	private static final class Encoder {

		private static EncodeResult encode(DType dtype, Object data) {
			PType signed = ((DType.Primitive) dtype).ptype();
			MemorySegment seg = switch (signed) {
				case I8 -> {
					byte[] arr = (byte[]) data;
					MemorySegment s = Arena.ofAuto().allocate(arr.length);
					for (int i = 0; i < arr.length; i++) {
						byte v = arr[i];
						s.set(ValueLayout.JAVA_BYTE, i, (byte) ((v << 1) ^ (v >> 7)));
					}
					yield s;
				}
				case I16 -> {
					short[] arr = (short[]) data;
					MemorySegment s = Arena.ofAuto().allocate((long) arr.length * 2, 2);
					for (int i = 0; i < arr.length; i++) {
						short v = arr[i];
						s.setAtIndex(PTypeIO.LE_SHORT, i, (short) ((v << 1) ^ (v >> 15)));
					}
					yield s;
				}
				case I32 -> {
					int[] arr = (int[]) data;
					MemorySegment s = Arena.ofAuto().allocate((long) arr.length * 4, 4);
					for (int i = 0; i < arr.length; i++) {
						int v = arr[i];
						s.setAtIndex(PTypeIO.LE_INT, i, (v << 1) ^ (v >> 31));
					}
					yield s;
				}
				case I64 -> {
					long[] arr = (long[]) data;
					MemorySegment s = Arena.ofAuto().allocate((long) arr.length * 8, 8);
					for (int i = 0; i < arr.length; i++) {
						long v = arr[i];
						s.setAtIndex(PTypeIO.LE_LONG, i, (v << 1) ^ (v >> 63));
					}
					yield s;
				}
				default -> throw new VortexException(EncodingId.VORTEX_ZIGZAG, "unsupported ptype: " + signed);
			};
			EncodeNode child = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
			EncodeNode root = new EncodeNode(EncodingId.VORTEX_ZIGZAG, null, new EncodeNode[]{child}, new int[0]);
			return new EncodeResult(root, List.of(seg), null, null);
		}
	}

	private static final class Decoder {

		private static Array decode(DecodeContext ctx) {
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
						int u = Short.toUnsignedInt(src.get(PTypeIO.LE_SHORT, i * 2));
						dst.set(PTypeIO.LE_SHORT, i * 2, (short) ((u >>> 1) ^ -(u & 1)));
					}
					yield new ShortArray(ctx.dtype(), n, dst, ArrayStats.empty());
				}
				case I32 -> {
					for (long i = 0; i < n; i++) {
						int u = src.get(PTypeIO.LE_INT, i * 4);
						dst.set(PTypeIO.LE_INT, i * 4, (u >>> 1) ^ -(u & 1));
					}
					yield new IntArray(ctx.dtype(), n, dst, ArrayStats.empty());
				}
				case I64 -> {
					for (long i = 0; i < n; i++) {
						long u = src.get(PTypeIO.LE_LONG, i * 8);
						dst.set(PTypeIO.LE_LONG, i * 8, (u >>> 1) ^ -(u & 1));
					}
					yield new LongArray(ctx.dtype(), n, dst, ArrayStats.empty());
				}
				default -> throw new VortexException(EncodingId.VORTEX_ZIGZAG, "unreachable");
			};
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
	}
}
