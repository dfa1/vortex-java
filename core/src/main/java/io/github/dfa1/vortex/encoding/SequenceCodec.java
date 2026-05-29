package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Decoder for {@code vortex.sequence}: {@code A[i] = base + i * multiplier}.
///
/// No buffers, no children. Metadata is a protobuf {@code SequenceMetadata}
/// with {@code base} (tag 1) and {@code multiplier} (tag 2) as {@code ScalarValue}.
/// Output is allocated on the heap; not backed by the file's mapped region.
public final class SequenceCodec implements Codec {

	private static Array decodeInteger(
			EncodingProtos.SequenceMetadata meta, PType pt, long n, DType dtype
	) {
		long base = signedValue(meta.getBase());
		long mul = signedValue(meta.getMultiplier());
		int elemBytes = pt.byteSize();
		ByteBuffer buf = ByteBuffer.allocate((int) (n * elemBytes)).order(ByteOrder.LITTLE_ENDIAN);
		for (long i = 0; i < n; i++) {
			long v = base + i * mul;
			switch (pt) {
				case I8, U8 -> buf.put((byte) v);
				case I16, U16 -> buf.putShort((short) v);
				case I32, U32 -> buf.putInt((int) v);
				case I64, U64 -> buf.putLong(v);
				default -> throw new IllegalStateException("unreachable");
			}
		}
		buf.flip();
		return new Array(dtype, n, new MemorySegment[]{MemorySegment.ofBuffer(buf)},
				Array.NO_CHILDREN, ArrayStats.empty());
	}

	private static Array decodeF32(EncodingProtos.SequenceMetadata meta, long n, DType dtype) {
		float base = meta.getBase().getF32Value();
		float mul = meta.getMultiplier().getF32Value();
		ByteBuffer buf = ByteBuffer.allocate((int) (n * 4)).order(ByteOrder.LITTLE_ENDIAN);
		for (long i = 0; i < n; i++) {
			buf.putFloat(base + i * mul);
		}
		buf.flip();
		return new Array(dtype, n, new MemorySegment[]{MemorySegment.ofBuffer(buf)},
				Array.NO_CHILDREN, ArrayStats.empty());
	}

	private static Array decodeF64(EncodingProtos.SequenceMetadata meta, long n, DType dtype) {
		double base = meta.getBase().getF64Value();
		double mul = meta.getMultiplier().getF64Value();
		ByteBuffer buf = ByteBuffer.allocate((int) (n * 8)).order(ByteOrder.LITTLE_ENDIAN);
		for (long i = 0; i < n; i++) {
			buf.putDouble(base + i * mul);
		}
		buf.flip();
		return new Array(dtype, n, new MemorySegment[]{MemorySegment.ofBuffer(buf)},
				Array.NO_CHILDREN, ArrayStats.empty());
	}

	private static long signedValue(dev.vortex.proto.ScalarProtos.ScalarValue sv) {
		return switch (sv.getKindCase()) {
			case INT64_VALUE -> sv.getInt64Value();
			case UINT64_VALUE -> sv.getUint64Value();
			case KIND_NOT_SET -> 0L;
			default -> throw new IllegalStateException("vortex.sequence: unexpected scalar kind " + sv.getKindCase());
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
			throw new IllegalStateException("vortex.sequence: missing metadata");
		}
		byte[] metaBytes = new byte[metaBuf.remaining()];
		metaBuf.duplicate().get(metaBytes);

		EncodingProtos.SequenceMetadata meta;
		try {
			meta = EncodingProtos.SequenceMetadata.parseFrom(metaBytes);
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalStateException("vortex.sequence: invalid metadata", e);
		}

		if (!(ctx.dtype() instanceof DType.Primitive p)) {
			throw new IllegalStateException("vortex.sequence: expected primitive dtype, got " + ctx.dtype());
		}

		long n = ctx.rowCount();
		PType pt = p.ptype();
		return switch (pt) {
			case I8, I16, I32, I64, U8, U16, U32, U64 -> decodeInteger(meta, pt, n, ctx.dtype());
			case F32 -> decodeF32(meta, n, ctx.dtype());
			case F64 -> decodeF64(meta, n, ctx.dtype());
			case F16 -> throw new IllegalStateException("vortex.sequence: F16 not supported");
		};
	}
}
