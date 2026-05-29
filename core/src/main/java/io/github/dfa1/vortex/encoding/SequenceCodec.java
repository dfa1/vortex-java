package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Decoder for {@code vortex.sequence}: {@code A[i] = base + i * multiplier}.
///
/// No buffers, no children. Metadata is a protobuf {@code SequenceMetadata}
/// with {@code base} (tag 1) and {@code multiplier} (tag 2) as {@code ScalarValue}.
/// Output is allocated on the heap; not backed by the file's mapped region.
public final class SequenceCodec implements Codec {

	private static final ValueLayout.OfLong   LE_LONG   = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfInt    LE_INT    = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfShort  LE_SHORT  = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfDouble LE_DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfFloat  LE_FLOAT  = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private static Array decodeInteger(
			EncodingProtos.SequenceMetadata meta, PType pt, long n, DType dtype, Arena arena
	) {
		long base = signedValue(meta.getBase());
		long mul = signedValue(meta.getMultiplier());
		int elemBytes = pt.byteSize();
		MemorySegment seg = arena.allocate(n * elemBytes);
		for (long i = 0; i < n; i++) {
			long v = base + i * mul;
			switch (pt) {
				case I8, U8 -> seg.set(ValueLayout.JAVA_BYTE, i, (byte) v);
				case I16, U16 -> seg.setAtIndex(LE_SHORT, i, (short) v);
				case I32, U32 -> seg.setAtIndex(LE_INT, i, (int) v);
				case I64, U64 -> seg.setAtIndex(LE_LONG, i, v);
				default -> throw new IllegalStateException("unreachable");
			}
		}
		MemorySegment ro = seg.asReadOnly();
		return switch (pt) {
			case I64, U64 -> new LongArray(dtype, n, ro, ArrayStats.empty());
			case I32, U32 -> new IntArray(dtype, n, ro, ArrayStats.empty());
			case I16, U16 -> new ShortArray(dtype, n, ro, ArrayStats.empty());
			case I8, U8   -> new ByteArray(dtype, n, ro, ArrayStats.empty());
			default -> throw new VortexException(CodecId.VORTEX_SEQUENCE, "unsupported ptype " + pt);
		};
	}

	private static Array decodeF32(EncodingProtos.SequenceMetadata meta, long n, DType dtype, Arena arena) {
		float base = meta.getBase().getF32Value();
		float mul = meta.getMultiplier().getF32Value();
		MemorySegment seg = arena.allocate(n * 4L);
		for (long i = 0; i < n; i++) {
			seg.setAtIndex(LE_FLOAT, i, base + i * mul);
		}
		return new FloatArray(dtype, n, seg.asReadOnly(), ArrayStats.empty());
	}

	private static Array decodeF64(EncodingProtos.SequenceMetadata meta, long n, DType dtype, Arena arena) {
		double base = meta.getBase().getF64Value();
		double mul = meta.getMultiplier().getF64Value();
		MemorySegment seg = arena.allocate(n * 8L);
		for (long i = 0; i < n; i++) {
			seg.setAtIndex(LE_DOUBLE, i, base + i * mul);
		}
		return new DoubleArray(dtype, n, seg.asReadOnly(), ArrayStats.empty());
	}

	private static long signedValue(dev.vortex.proto.ScalarProtos.ScalarValue sv) {
		return switch (sv.getKindCase()) {
			case INT64_VALUE -> sv.getInt64Value();
			case UINT64_VALUE -> sv.getUint64Value();
			case KIND_NOT_SET -> 0L;
			default -> throw new VortexException(CodecId.VORTEX_SEQUENCE, "unexpected scalar kind " + sv.getKindCase());
		};
	}

	@Override
	public CodecId encodingId() {
		return CodecId.VORTEX_SEQUENCE;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new UnsupportedOperationException("encode not supported by " + encodingId());
	}

	@Override
	public Array decode(DecodeContext ctx) {
		ByteBuffer metaBuf = ctx.metadata();
		if (metaBuf == null || !metaBuf.hasRemaining()) {
			throw new VortexException(CodecId.VORTEX_SEQUENCE, "missing metadata");
		}
		EncodingProtos.SequenceMetadata meta;
		try {
			meta = EncodingProtos.SequenceMetadata.parseFrom(metaBuf.duplicate());
		} catch (InvalidProtocolBufferException e) {
			throw new VortexException(CodecId.VORTEX_SEQUENCE, "invalid metadata", e);
		}

		if (!(ctx.dtype() instanceof DType.Primitive p)) {
			throw new VortexException(CodecId.VORTEX_SEQUENCE, "expected primitive dtype, got " + ctx.dtype());
		}

		long n = ctx.rowCount();
		PType pt = p.ptype();
		return switch (pt) {
			case I8, I16, I32, I64, U8, U16, U32, U64 -> decodeInteger(meta, pt, n, ctx.dtype(), ctx.arena());
			case F32 -> decodeF32(meta, n, ctx.dtype(), ctx.arena());
			case F64 -> decodeF64(meta, n, ctx.dtype(), ctx.arena());
			case F16 -> throw new VortexException(CodecId.VORTEX_SEQUENCE, "F16 not supported");
		};
	}
}
