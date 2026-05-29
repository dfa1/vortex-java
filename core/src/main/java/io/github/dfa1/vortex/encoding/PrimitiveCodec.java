package io.github.dfa1.vortex.encoding;

import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Codec for `vortex.primitive` — raw little-endian primitive arrays.
/// Encodes all [DType.Primitive] types; embeds min/max stats as Protobuf ScalarValue bytes.
public final class PrimitiveCodec implements Codec {

	private static ByteBuffer encodePrimitive(PType ptype, Object data) {
		int elementBytes = ptype.byteSize();
		return switch (ptype) {
			case I8, U8 -> ByteBuffer.wrap((byte[]) data);
			case I16, U16 -> {
				short[] arr = (short[]) data;
				var bb = ByteBuffer.allocate(arr.length * elementBytes).order(ByteOrder.LITTLE_ENDIAN);
				for (short v : arr) {
					bb.putShort(v);
				}
				yield bb.flip();
			}
			case I32, U32 -> {
				int[] arr = (int[]) data;
				var bb = ByteBuffer.allocate(arr.length * elementBytes).order(ByteOrder.LITTLE_ENDIAN);
				for (int v : arr) {
					bb.putInt(v);
				}
				yield bb.flip();
			}
			case I64, U64 -> {
				long[] arr = (long[]) data;
				var bb = ByteBuffer.allocate(arr.length * elementBytes).order(ByteOrder.LITTLE_ENDIAN);
				for (long v : arr) {
					bb.putLong(v);
				}
				yield bb.flip();
			}
			case F32 -> {
				float[] arr = (float[]) data;
				var bb = ByteBuffer.allocate(arr.length * elementBytes).order(ByteOrder.LITTLE_ENDIAN);
				for (float v : arr) {
					bb.putFloat(v);
				}
				yield bb.flip();
			}
			case F64 -> {
				double[] arr = (double[]) data;
				var bb = ByteBuffer.allocate(arr.length * elementBytes).order(ByteOrder.LITTLE_ENDIAN);
				for (double v : arr) {
					bb.putDouble(v);
				}
				yield bb.flip();
			}
			case F16 -> throw new UnsupportedOperationException("F16 not supported");
		};
	}

	private static byte[][] computeStats(PType ptype, Object data) {
		return switch (ptype) {
			case I8 -> {
				byte[] arr = (byte[]) data;
				if (arr.length == 0) {
					yield null;
				}
				long min = arr[0], max = arr[0];
				for (byte v : arr) {
					if (v < min) {
						min = v;
					}
					if (v > max) {
						max = v;
					}
				}
				yield new byte[][]{scalarI64(min), scalarI64(max)};
			}
			case I16 -> {
				short[] arr = (short[]) data;
				if (arr.length == 0) {
					yield null;
				}
				long min = arr[0], max = arr[0];
				for (short v : arr) {
					if (v < min) {
						min = v;
					}
					if (v > max) {
						max = v;
					}
				}
				yield new byte[][]{scalarI64(min), scalarI64(max)};
			}
			case I32 -> {
				int[] arr = (int[]) data;
				if (arr.length == 0) {
					yield null;
				}
				long min = arr[0], max = arr[0];
				for (int v : arr) {
					if (v < min) {
						min = v;
					}
					if (v > max) {
						max = v;
					}
				}
				yield new byte[][]{scalarI64(min), scalarI64(max)};
			}
			case I64 -> {
				long[] arr = (long[]) data;
				if (arr.length == 0) {
					yield null;
				}
				long min = arr[0], max = arr[0];
				for (long v : arr) {
					if (v < min) {
						min = v;
					}
					if (v > max) {
						max = v;
					}
				}
				yield new byte[][]{scalarI64(min), scalarI64(max)};
			}
			case U8 -> {
				byte[] arr = (byte[]) data;
				if (arr.length == 0) {
					yield null;
				}
				long min = Byte.toUnsignedInt(arr[0]), max = Byte.toUnsignedInt(arr[0]);
				for (byte v : arr) {
					long uv = Byte.toUnsignedInt(v);
					if (uv < min) {
						min = uv;
					}
					if (uv > max) {
						max = uv;
					}
				}
				yield new byte[][]{scalarU64(min), scalarU64(max)};
			}
			case U16 -> {
				short[] arr = (short[]) data;
				if (arr.length == 0) {
					yield null;
				}
				long min = Short.toUnsignedInt(arr[0]), max = Short.toUnsignedInt(arr[0]);
				for (short v : arr) {
					long uv = Short.toUnsignedInt(v);
					if (uv < min) {
						min = uv;
					}
					if (uv > max) {
						max = uv;
					}
				}
				yield new byte[][]{scalarU64(min), scalarU64(max)};
			}
			case U32 -> {
				int[] arr = (int[]) data;
				if (arr.length == 0) {
					yield null;
				}
				long min = Integer.toUnsignedLong(arr[0]), max = Integer.toUnsignedLong(arr[0]);
				for (int v : arr) {
					long uv = Integer.toUnsignedLong(v);
					if (uv < min) {
						min = uv;
					}
					if (uv > max) {
						max = uv;
					}
				}
				yield new byte[][]{scalarU64(min), scalarU64(max)};
			}
			case U64 -> {
				long[] arr = (long[]) data;
				if (arr.length == 0) {
					yield null;
				}
				long min = arr[0], max = arr[0];
				for (long v : arr) {
					if (Long.compareUnsigned(v, min) < 0) {
						min = v;
					}
					if (Long.compareUnsigned(v, max) > 0) {
						max = v;
					}
				}
				yield new byte[][]{scalarU64(min), scalarU64(max)};
			}
			case F32 -> {
				float[] arr = (float[]) data;
				if (arr.length == 0) {
					yield null;
				}
				float min = arr[0], max = arr[0];
				for (float v : arr) {
					if (v < min) {
						min = v;
					}
					if (v > max) {
						max = v;
					}
				}
				yield new byte[][]{scalarF32(min), scalarF32(max)};
			}
			case F64 -> {
				double[] arr = (double[]) data;
				if (arr.length == 0) {
					yield null;
				}
				double min = arr[0], max = arr[0];
				for (double v : arr) {
					if (v < min) {
						min = v;
					}
					if (v > max) {
						max = v;
					}
				}
				yield new byte[][]{scalarF64(min), scalarF64(max)};
			}
			case F16 -> null;
		};
	}

	private static byte[] scalarI64(long v) {
		return ScalarProtos.ScalarValue.newBuilder().setInt64Value(v).build().toByteArray();
	}

	private static byte[] scalarU64(long v) {
		return ScalarProtos.ScalarValue.newBuilder().setUint64Value(v).build().toByteArray();
	}

	// ── Encoding ──────────────────────────────────────────────────────────────

	private static byte[] scalarF32(float v) {
		return ScalarProtos.ScalarValue.newBuilder().setF32Value(v).build().toByteArray();
	}

	// ── Stats ─────────────────────────────────────────────────────────────────

	private static byte[] scalarF64(double v) {
		return ScalarProtos.ScalarValue.newBuilder().setF64Value(v).build().toByteArray();
	}

	@Override
	public CodecId encodingId() {
		return CodecId.VORTEX_PRIMITIVE;
	}

	@Override
	public boolean accepts(DType dtype) {
		return dtype instanceof DType.Primitive;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		PType ptype = ((DType.Primitive) dtype).ptype();
		ByteBuffer buf = encodePrimitive(ptype, data);
		byte[] min = null;
		byte[] max = null;
		byte[][] stats = computeStats(ptype, data);
		if (stats != null) {
			min = stats[0];
			max = stats[1];
		}
		return EncodeResult.simple(encodingId(), buf, min, max);
	}

	@Override
	public Array decode(DecodeContext ctx) {
		MemorySegment buf = ctx.buffer(0);
		long n = ctx.rowCount();
		DType dt = ctx.dtype();
		PType ptype = ((DType.Primitive) dt).ptype();
		var stats = ctx.node().stats();
		return switch (ptype) {
			case I64, U64 -> new LongArray(dt, n, buf, stats);
			case I32, U32 -> new IntArray(dt, n, buf, stats);
			case F64 -> new DoubleArray(dt, n, buf, stats);
			case F32 -> new FloatArray(dt, n, buf, stats);
			case I16, U16 -> new ShortArray(dt, n, buf, stats);
			case I8, U8 -> new ByteArray(dt, n, buf, stats);
			default -> throw new VortexException(CodecId.VORTEX_PRIMITIVE, "unsupported ptype " + ptype);
		};
	}
}
