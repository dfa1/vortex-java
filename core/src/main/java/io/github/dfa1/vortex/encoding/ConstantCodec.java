package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
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
public final class ConstantCodec implements Codec {

	private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private static long scalarToRawBits(ScalarProtos.ScalarValue scalar, PType ptype) {
		return switch (scalar.getKindCase()) {
			case INT64_VALUE -> scalar.getInt64Value();
			case UINT64_VALUE -> scalar.getUint64Value();
			case F32_VALUE -> Float.floatToRawIntBits(scalar.getF32Value());
			case F64_VALUE -> Double.doubleToRawLongBits(scalar.getF64Value());
			case KIND_NOT_SET -> 0L;
			default -> throw new VortexException(CodecId.VORTEX_CONSTANT,
					"unexpected scalar kind " + scalar.getKindCase());
		};
	}

	private static void writeRaw(ByteBuffer buf, PType ptype, long rawBits) {
		switch (ptype.byteSize()) {
			case 1 -> buf.put((byte) rawBits);
			case 2 -> buf.putShort((short) rawBits);
			case 4 -> buf.putInt((int) rawBits);
			case 8 -> buf.putLong(rawBits);
			default -> throw new VortexException(CodecId.VORTEX_CONSTANT, "unsupported ptype " + ptype);
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	@Override
	public CodecId encodingId() {
		return CodecId.VORTEX_CONSTANT;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new UnsupportedOperationException("encode not supported by " + encodingId());
	}

	@Override
	public Array decode(DecodeContext ctx) {
		MemorySegment scalarBuf = ctx.buffer(0);
		ScalarProtos.ScalarValue scalar;
		try {
			scalar = ScalarProtos.ScalarValue.parseFrom(scalarBuf.asByteBuffer());
		} catch (InvalidProtocolBufferException e) {
			throw new VortexException(CodecId.VORTEX_CONSTANT, "invalid scalar value", e);
		}

		long n = ctx.rowCount();

		if (ctx.dtype() instanceof DType.Utf8 || ctx.dtype() instanceof DType.Binary) {
			return decodeString(ctx, scalar, n);
		}

		if (!(ctx.dtype() instanceof DType.Primitive p)) {
			throw new VortexException(CodecId.VORTEX_CONSTANT, "unsupported dtype " + ctx.dtype());
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
			default -> throw new VortexException(CodecId.VORTEX_CONSTANT, "unsupported ptype " + ptype);
		};
	}

	private static Array decodeString(DecodeContext ctx, ScalarProtos.ScalarValue scalar, long n) {
		byte[] strBytes = scalar.hasStringValue()
				? scalar.getStringValue().getBytes(StandardCharsets.UTF_8)
				: scalar.getBytesValue().toByteArray();

		int strLen = strBytes.length;

		// bytes: one copy of the string per element (standard varbin layout)
		MemorySegment bytesSeg = ctx.arena().allocate((long) n * strLen);
		for (long i = 0; i < n; i++) {
			MemorySegment.copy(MemorySegment.ofArray(strBytes), 0L, bytesSeg, i * strLen, strLen);
		}

		// offsets: sequential, stride = strLen
		MemorySegment offsetsSeg = ctx.arena().allocate((n + 1) * 4L, 4);
		for (long i = 0; i <= n; i++) {
			offsetsSeg.setAtIndex(LE_INT, i, (int) (i * strLen));
		}

		DType i32 = new DType.Primitive(PType.I32, false);
		Array offsets = new IntArray(i32, n + 1, offsetsSeg.asReadOnly(), ArrayStats.empty());
		return new VarBinArray(ctx.dtype(), n, bytesSeg.asReadOnly(), offsets, PType.I32, ArrayStats.empty());
	}
}
