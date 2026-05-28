package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Decoder for {@code vortex.constant} — all elements share the same value.
///
/// <p>No metadata (empty bytes). Buffer 0: the constant value as raw {@code ScalarValue}
/// proto bytes. No children.
///
/// <p>Decode: fill an output buffer of {@code rowCount} elements with the constant value.
public final class ConstantCodec implements Codec {

	private static long scalarToRawBits(ScalarProtos.ScalarValue scalar, PType ptype) {
		return switch (scalar.getKindCase()) {
			case INT64_VALUE -> scalar.getInt64Value();
			case UINT64_VALUE -> scalar.getUint64Value();
			case F32_VALUE -> Float.floatToRawIntBits(scalar.getF32Value());
			case F64_VALUE -> Double.doubleToRawLongBits(scalar.getF64Value());
			case KIND_NOT_SET -> 0L;
			default -> throw new IllegalStateException(
					"vortex.constant: unexpected scalar kind " + scalar.getKindCase());
		};
	}

	private static void writeRaw(ByteBuffer buf, PType ptype, long rawBits) {
		switch (ptype.byteSize()) {
			case 1 -> buf.put((byte) rawBits);
			case 2 -> buf.putShort((short) rawBits);
			case 4 -> buf.putInt((int) rawBits);
			case 8 -> buf.putLong(rawBits);
			default -> throw new UnsupportedOperationException("vortex.constant: unsupported ptype " + ptype);
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_CONSTANT;
	}

	@Override
	public Array decode(DecodeContext ctx) {
		if (!(ctx.dtype() instanceof DType.Primitive p)) {
			throw new IllegalStateException("vortex.constant: expected primitive dtype, got " + ctx.dtype());
		}

		MemorySegment scalarBuf = ctx.buffer(0);
		byte[] scalarBytes = scalarBuf.toArray(ValueLayout.JAVA_BYTE);

		ScalarProtos.ScalarValue scalar;
		try {
			scalar = ScalarProtos.ScalarValue.parseFrom(scalarBytes);
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalStateException("vortex.constant: invalid scalar value", e);
		}

		PType ptype = p.ptype();
		long n = ctx.rowCount();
		int elemBytes = ptype.byteSize();
		long rawBits = scalarToRawBits(scalar, ptype);

		byte[] outBytes = new byte[(int) (n * elemBytes)];
		ByteBuffer out = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN);
		for (long i = 0; i < n; i++) {
			writeRaw(out, ptype, rawBits);
		}

		return new Array(ctx.dtype(), n,
				new MemorySegment[]{MemorySegment.ofArray(outBytes)}, Array.NO_CHILDREN, ArrayStats.empty());
	}
}
