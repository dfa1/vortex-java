package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Decoder for {@code fastlanes.for} (Frame of Reference).
///
/// <p>Metadata: raw {@code ScalarValue} protobuf bytes — the reference (minimum) value.
/// Child slot 0: encoded residuals array (same dtype as parent, typically bitpacked).
/// Decode: {@code output[i] = encoded[i] + reference} (wrapping arithmetic).
public final class FrameOfReferenceEncoding implements Encoding {

	private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfInt   LE_INT   = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfLong  LE_LONG  = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private static long referenceValue(ScalarProtos.ScalarValue scalar) {
		return switch (scalar.getKindCase()) {
			case INT64_VALUE -> scalar.getInt64Value();
			case UINT64_VALUE -> scalar.getUint64Value();
			case KIND_NOT_SET -> 0L;
			default -> throw new VortexException(EncodingId.FASTLANES_FOR,
					"unexpected scalar kind " + scalar.getKindCase());
		};
	}

	private static MemorySegment applyReference(MemorySegment src, long n, PType ptype, long ref, SegmentAllocator arena) {
		int wordBytes = ptype.byteSize();
		MemorySegment dst = arena.allocate(n * wordBytes);
		switch (ptype) {
			case I8, U8 -> {
				for (long off = 0, end = n; off < end; off++) {
					byte v = src.get(ValueLayout.JAVA_BYTE, off);
					dst.set(ValueLayout.JAVA_BYTE, off, (byte) (v + (byte) ref));
				}
			}
			case I16, U16 -> {
				for (long off = 0, end = n * 2; off < end; off += 2) {
					short v = src.get(LE_SHORT, off);
					dst.set(LE_SHORT, off, (short) (v + (short) ref));
				}
			}
			case I32, U32 -> {
				for (long off = 0, end = n * 4; off < end; off += 4) {
					int v = src.get(LE_INT, off);
					dst.set(LE_INT, off, v + (int) ref);
				}
			}
			case I64, U64 -> {
				for (long off = 0, end = n * 8; off < end; off += 8) {
					long v = src.get(LE_LONG, off);
					dst.set(LE_LONG, off, v + ref);
				}
			}
			default -> throw new VortexException(EncodingId.FASTLANES_FOR,
					"unsupported ptype " + ptype);
		}
		return dst;
	}

	@Override
	public EncodingId encodingId() {
		return EncodingId.FASTLANES_FOR;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new UnsupportedOperationException("encode not supported by " + encodingId());
	}

	@Override
	public Array decode(DecodeContext ctx) {
		ByteBuffer rawMeta = ctx.metadata();
		if (rawMeta == null || !rawMeta.hasRemaining()) {
			throw new VortexException(EncodingId.FASTLANES_FOR, "missing metadata");
		}
		ScalarProtos.ScalarValue scalar;
		try {
			scalar = ScalarProtos.ScalarValue.parseFrom(rawMeta.duplicate());
		} catch (InvalidProtocolBufferException e) {
			throw new VortexException(EncodingId.FASTLANES_FOR, "invalid metadata", e);
		}

		Array encoded = ctx.decodeChild(0);

		if (!(ctx.dtype() instanceof DType.Primitive p)) {
			throw new VortexException(EncodingId.FASTLANES_FOR, "expected primitive dtype, got " + ctx.dtype());
		}

		long ref = referenceValue(scalar);
		if (ref == 0L) {
			return encoded;
		}

		MemorySegment src = encoded.buffer(0);
		long n = ctx.rowCount();
		MemorySegment dst = applyReference(src, n, p.ptype(), ref, ctx.arena());
		return switch (p.ptype()) {
			case I64, U64 -> new LongArray(ctx.dtype(), n, dst, ArrayStats.empty());
			case I32, U32 -> new IntArray(ctx.dtype(), n, dst, ArrayStats.empty());
			case F64 -> new DoubleArray(ctx.dtype(), n, dst, ArrayStats.empty());
			case I16, U16 -> new ShortArray(ctx.dtype(), n, dst, ArrayStats.empty());
			case I8, U8   -> new ByteArray(ctx.dtype(), n, dst, ArrayStats.empty());
			default -> throw new VortexException(EncodingId.FASTLANES_FOR, "unsupported ptype " + p.ptype());
		};
	}
}
