package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.GenericArray;
import io.github.dfa1.vortex.core.array.NullArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/// Decoder for {@code vortex.constant} — all elements share the same value.
///
/// <p>No metadata (empty bytes). Buffer 0: the constant value as raw {@code ScalarValue}
/// proto bytes. No children.
///
/// <p>Decode: fill an output buffer of {@code rowCount} elements with the constant value.
public final class ConstantEncoding implements Encoding {

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_CONSTANT;
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
			if (!(dtype instanceof DType.Primitive p)) {
				throw new VortexException(EncodingId.VORTEX_CONSTANT, "encode only supports Primitive dtype, got " + dtype);
			}
			PType ptype = p.ptype();
			long firstRaw = readFirstRaw(data, ptype);
			assertAllEqual(data, ptype, firstRaw);

			ScalarProtos.ScalarValue scalar = buildScalar(ptype, firstRaw);
			return EncodeResult.simple(EncodingId.VORTEX_CONSTANT, MemorySegment.ofArray(scalar.toByteArray()));
		}

		private static long readFirstRaw(Object data, PType ptype) {
			return switch (ptype) {
				case I8, U8 -> ((byte[]) data).length > 0 ? ((byte[]) data)[0] : 0L;
				case I16, U16 -> ((short[]) data).length > 0 ? ((short[]) data)[0] : 0L;
				case I32, U32 -> ((int[]) data).length > 0 ? ((int[]) data)[0] : 0L;
				case I64, U64 -> ((long[]) data).length > 0 ? ((long[]) data)[0] : 0L;
				case F32 -> ((float[]) data).length > 0 ? Float.floatToRawIntBits(((float[]) data)[0]) : 0L;
				case F64 -> ((double[]) data).length > 0 ? Double.doubleToRawLongBits(((double[]) data)[0]) : 0L;
				default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype: " + ptype);
			};
		}

		private static void assertAllEqual(Object data, PType ptype, long firstRaw) {
			int len = switch (ptype) {
				case I8, U8 -> ((byte[]) data).length;
				case I16, U16 -> ((short[]) data).length;
				case I32, U32 -> ((int[]) data).length;
				case I64, U64 -> ((long[]) data).length;
				case F32 -> ((float[]) data).length;
				case F64 -> ((double[]) data).length;
				default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype: " + ptype);
			};
			for (int i = 1; i < len; i++) {
				long raw = switch (ptype) {
					case I8, U8 -> ((byte[]) data)[i];
					case I16, U16 -> ((short[]) data)[i];
					case I32, U32 -> ((int[]) data)[i];
					case I64, U64 -> ((long[]) data)[i];
					case F32 -> Float.floatToRawIntBits(((float[]) data)[i]);
					case F64 -> Double.doubleToRawLongBits(((double[]) data)[i]);
					default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype: " + ptype);
				};
				if (raw != firstRaw) {
					throw new VortexException(EncodingId.VORTEX_CONSTANT, "not a constant array: element " + i + " differs");
				}
			}
		}

		private static ScalarProtos.ScalarValue buildScalar(PType ptype, long rawBits) {
			return switch (ptype) {
				case U8, U16, U32, U64 -> ScalarProtos.ScalarValue.newBuilder().setUint64Value(rawBits).build();
				case I8, I16, I32, I64 -> ScalarProtos.ScalarValue.newBuilder().setInt64Value(rawBits).build();
				case F32 -> ScalarProtos.ScalarValue.newBuilder().setF32Value(Float.intBitsToFloat((int) rawBits)).build();
				case F64 -> ScalarProtos.ScalarValue.newBuilder().setF64Value(Double.longBitsToDouble(rawBits)).build();
				default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype: " + ptype);
			};
		}
	}

	private static final class Decoder {

		private static Array decode(DecodeContext ctx) {
			MemorySegment scalarBuf = ctx.buffer(0);
			ScalarProtos.ScalarValue scalar;
			try {
				scalar = ScalarProtos.ScalarValue.parseFrom(scalarBuf.asByteBuffer());
			} catch (InvalidProtocolBufferException e) {
				throw new VortexException(EncodingId.VORTEX_CONSTANT, "invalid scalar value", e);
			}

			long n = ctx.rowCount();

			if (ctx.dtype() instanceof DType.Null) {
				return new NullArray(ctx.dtype(), n);
			}

			if (ctx.dtype() instanceof DType.Utf8 || ctx.dtype() instanceof DType.Binary) {
				return decodeString(ctx, scalar, n);
			}

			if (ctx.dtype() instanceof DType.Bool) {
				return decodeBool(ctx, scalar, n);
			}

			if (ctx.dtype() instanceof DType.Decimal) {
				return decodeDecimal(ctx, scalar, n);
			}

			if (ctx.dtype() instanceof DType.Extension ext) {
				// Decode using the storage dtype, re-wrap with the extension dtype
				var storageCtx = new DecodeContext(ctx.node(), ext.storageDType(), ctx.rowCount(),
						ctx.segmentBuffers(), ctx.registry(), ctx.arena());
				Array storage = decode(storageCtx);
				return new GenericArray(ctx.dtype(), n, storage.buffer(0));
			}

			if (!(ctx.dtype() instanceof DType.Primitive p)) {
				throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported dtype " + ctx.dtype());
			}

			PType ptype = p.ptype();
			int elemBytes = ptype.byteSize();
			long rawBits = scalarToRawBits(scalar, ptype);

			MemorySegment outSeg = ctx.arena().allocate(n * elemBytes);
			ByteBuffer out = outSeg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
			for (long i = 0; i < n; i++) {
				writeRaw(out, ptype, rawBits);
			}

			MemorySegment ro = outSeg.asReadOnly();
			return switch (ptype) {
				case I64, U64 -> new LongArray(ctx.dtype(), n, ro, ArrayStats.empty());
				case I32, U32 -> new IntArray(ctx.dtype(), n, ro, ArrayStats.empty());
				case F64 -> new DoubleArray(ctx.dtype(), n, ro, ArrayStats.empty());
				case F32 -> new FloatArray(ctx.dtype(), n, ro, ArrayStats.empty());
				case I16, U16 -> new ShortArray(ctx.dtype(), n, ro, ArrayStats.empty());
				case I8, U8   -> new ByteArray(ctx.dtype(), n, ro, ArrayStats.empty());
				default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype " + ptype);
			};
		}

		private static Array decodeDecimal(DecodeContext ctx, ScalarProtos.ScalarValue scalar, long n) {
			// Decimal stored as i128 (16 bytes LE) in bytes_value
			byte[] elemBytes = scalar.getBytesValue().toByteArray();
			int elemLen = elemBytes.length;
			MemorySegment outSeg = ctx.arena().allocate(n * elemLen);
			MemorySegment elemSeg = MemorySegment.ofArray(elemBytes);
			for (long i = 0; i < n; i++) {
				MemorySegment.copy(elemSeg, 0L, outSeg, i * elemLen, elemLen);
			}
			return new GenericArray(ctx.dtype(), n, outSeg.asReadOnly());
		}

		private static Array decodeBool(DecodeContext ctx, ScalarProtos.ScalarValue scalar, long n) {
			boolean value = scalar.getBoolValue();
			long numBytes = (n + 7) >>> 3;
			MemorySegment seg = ctx.arena().allocate(numBytes);
			if (value) {
				for (long i = 0; i < numBytes; i++) {
					seg.set(ValueLayout.JAVA_BYTE, i, (byte) 0xFF);
				}
			}
			return new BoolArray(ctx.dtype(), n, seg.asReadOnly(), ArrayStats.empty());
		}

		private static Array decodeString(DecodeContext ctx, ScalarProtos.ScalarValue scalar, long n) {
			byte[] strBytes = scalar.hasStringValue()
					? scalar.getStringValue().getBytes(StandardCharsets.UTF_8)
					: scalar.getBytesValue().toByteArray();

			int strLen = strBytes.length;

			MemorySegment bytesSeg = ctx.arena().allocate((long) n * strLen);
			for (long i = 0; i < n; i++) {
				MemorySegment.copy(MemorySegment.ofArray(strBytes), 0L, bytesSeg, i * strLen, strLen);
			}

			MemorySegment offsetsSeg = ctx.arena().allocate((n + 1) * 4L, 4);
			for (long i = 0; i <= n; i++) {
				offsetsSeg.setAtIndex(PTypeIO.LE_INT, i, (int) (i * strLen));
			}

			DType i32 = new DType.Primitive(PType.I32, false);
			Array offsets = new IntArray(i32, n + 1, offsetsSeg.asReadOnly(), ArrayStats.empty());
			return new VarBinArray(ctx.dtype(), n, bytesSeg.asReadOnly(), offsets, PType.I32, ArrayStats.empty());
		}

		private static long scalarToRawBits(ScalarProtos.ScalarValue scalar, PType ptype) {
			return switch (scalar.getKindCase()) {
				case INT64_VALUE -> scalar.getInt64Value();
				case UINT64_VALUE -> scalar.getUint64Value();
				case F32_VALUE -> Float.floatToRawIntBits(scalar.getF32Value());
				case F64_VALUE -> Double.doubleToRawLongBits(scalar.getF64Value());
				case KIND_NOT_SET -> 0L;
				default -> throw new VortexException(EncodingId.VORTEX_CONSTANT,
						"unexpected scalar kind " + scalar.getKindCase());
			};
		}

		private static void writeRaw(ByteBuffer buf, PType ptype, long rawBits) {
			switch (ptype.byteSize()) {
				case 1 -> buf.put((byte) rawBits);
				case 2 -> buf.putShort((short) rawBits);
				case 4 -> buf.putInt((int) rawBits);
				case 8 -> buf.putLong(rawBits);
				default -> throw new VortexException(EncodingId.VORTEX_CONSTANT, "unsupported ptype " + ptype);
			}
		}
	}
}
