package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
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

		return new Array(ctx.dtype(), n,
				new MemorySegment[]{outSeg.asReadOnly()}, Array.NO_CHILDREN, ArrayStats.empty());
	}

	private static Array decodeString(DecodeContext ctx, ScalarProtos.ScalarValue scalar, long n) {
		byte[] strBytes = scalar.hasStringValue()
				? scalar.getStringValue().getBytes(StandardCharsets.UTF_8)
				: scalar.getBytesValue().toByteArray();

		int strLen = strBytes.length;

		// Store the string bytes once; all n offsets point into the same [0, strLen] range.
		// Offsets layout: [0, strLen, 0, strLen, ...] — each pair encodes the same slice.
		MemorySegment bytesSeg = ctx.arena().allocate(strLen);
		bytesSeg.asByteBuffer().put(strBytes);

		// Offsets: n+1 I32 values, alternating start/end of the single string.
		MemorySegment offsetsSeg = ctx.arena().allocate((n + 1) * 4L, 4);
		for (long i = 0; i <= n; i++) {
			// even index → 0 (start), odd → strLen (end); wraps back to 0 for the next string's start
			offsetsSeg.setAtIndex(LE_INT, i, (i % 2 == 0) ? 0 : strLen);
		}

		Array offsets = new Array(new DType.Primitive(PType.I32, false), n + 1,
				new MemorySegment[]{offsetsSeg.asReadOnly()}, Array.NO_CHILDREN, ArrayStats.empty());

		return new Array(ctx.dtype(), n,
				new MemorySegment[]{bytesSeg.asReadOnly()},
				new Array[]{offsets},
				ArrayStats.empty());
	}
}
