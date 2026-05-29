package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.DTypeProtos;
import dev.vortex.proto.EncodingProtos;
import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Decoder for {@code vortex.sparse}.
///
/// <p>Metadata: protobuf {@code SparseMetadata} wrapping a {@code PatchesMetadata}
/// (patch count, index offset, index ptype).
/// Buffer 0: fill value as raw {@code ScalarValue} proto bytes.
/// Child slot 0: patch indices (unsigned int, dtype from PatchesMetadata.indices_ptype).
/// Child slot 1: patch values (same dtype as parent).
///
/// <p>Decode: allocate output filled with fill_value, then overwrite
/// {@code output[indices[i] - offset] = values[i]} for each patch.
public final class SparseCodec implements Codec {

	private static Array decodeChildWithDtype(DecodeContext parent, int childIdx, DType dtype, long rowCount) {
		ArrayNode childNode = parent.node().children()[childIdx];
		DecodeContext childCtx = new DecodeContext(
				childNode, dtype, rowCount, parent.segmentBuffers(), parent.registry(), parent.arena());
		try {
			return parent.registry().decode(childCtx);
		} catch (Exception e) {
			throw new IllegalStateException("vortex.sparse: failed to decode child " + childIdx, e);
		}
	}

	private static void fillSegment(MemorySegment out, long n, PType ptype, ScalarProtos.ScalarValue scalar) {
		long fillLong = scalarToLong(scalar, ptype);
		int elemBytes = ptype.byteSize();
		ByteBuffer bb = out.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
		for (long i = 0; i < n; i++) {
			writeElem(bb, ptype, fillLong);
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private static void applyPatches(
			MemorySegment out, long n, PType valuePtype,
			MemorySegment idxSeg, MemorySegment valSeg,
			PType idxPtype, long numPatches, long offset
	) {
		int elemBytes = valuePtype.byteSize();
		ByteBuffer outBuf = out.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
		for (long i = 0; i < numPatches; i++) {
			long idx = readUnsignedIdx(idxSeg, i, idxPtype) - offset;
			if (idx < 0 || idx >= n) {
				throw new IllegalStateException(
						"vortex.sparse: patch index " + idx + " out of range [0," + n + ")");
			}
			long val = readElem(valSeg, i, valuePtype);
			outBuf.position((int) (idx * elemBytes));
			writeElem(outBuf, valuePtype, val);
		}
	}

	private static long readUnsignedIdx(MemorySegment seg, long i, PType ptype) {
		return switch (ptype) {
			case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
			case U16 -> Short.toUnsignedLong(
					seg.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 2));
			case U32 -> Integer.toUnsignedLong(
					seg.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 4));
			case U64 -> seg.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 8);
			default -> throw new IllegalStateException("vortex.sparse: non-unsigned index ptype " + ptype);
		};
	}

	private static long readElem(MemorySegment seg, long i, PType ptype) {
		return switch (ptype) {
			case I8, U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
			case I16, U16 -> Short.toUnsignedLong(
					seg.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 2));
			case I32, U32 -> Integer.toUnsignedLong(
					seg.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 4));
			case I64, U64, F32, F64 ->
					seg.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 8);
			default -> throw new UnsupportedOperationException("vortex.sparse: unsupported ptype " + ptype);
		};
	}

	private static void writeElem(ByteBuffer bb, PType ptype, long bits) {
		switch (ptype) {
			case I8, U8 -> bb.put((byte) bits);
			case I16, U16 -> bb.putShort((short) bits);
			case I32, U32 -> bb.putInt((int) bits);
			case I64, U64, F32, F64 -> bb.putLong(bits);
			default -> throw new UnsupportedOperationException("vortex.sparse: unsupported ptype " + ptype);
		}
	}

	private static long scalarToLong(ScalarProtos.ScalarValue scalar, PType ptype) {
		return switch (scalar.getKindCase()) {
			case INT64_VALUE -> scalar.getInt64Value();
			case UINT64_VALUE -> scalar.getUint64Value();
			case F32_VALUE -> Float.floatToRawIntBits(scalar.getF32Value());
			case F64_VALUE -> Double.doubleToRawLongBits(scalar.getF64Value());
			case KIND_NOT_SET -> 0L;
			default -> throw new IllegalStateException(
					"vortex.sparse: unexpected scalar kind " + scalar.getKindCase());
		};
	}

	// PType proto enum ordinals match Java PType ordinals (U8=0..F64=10)
	private static PType ptypeFromProto(DTypeProtos.PType proto) {
		return PType.values()[proto.getNumber()];
	}

	@Override
	public CodecId encodingId() {
		return CodecId.VORTEX_SPARSE;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		throw new UnsupportedOperationException("encode not supported by " + encodingId());
	}

	@Override
	public Array decode(DecodeContext ctx) {
		ByteBuffer rawMeta = ctx.metadata();
		if (rawMeta == null || !rawMeta.hasRemaining()) {
			throw new IllegalStateException("vortex.sparse: missing metadata");
		}
		byte[] metaBytes = new byte[rawMeta.remaining()];
		rawMeta.duplicate().get(metaBytes);

		EncodingProtos.SparseMetadata sparseMeta;
		try {
			sparseMeta = EncodingProtos.SparseMetadata.parseFrom(metaBytes);
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalStateException("vortex.sparse: invalid metadata", e);
		}

		EncodingProtos.PatchesMetadata patches = sparseMeta.getPatches();
		long numPatches = patches.getLen();
		long offset = patches.getOffset();
		PType indicesPtype = ptypeFromProto(patches.getIndicesPtype());

		if (!(ctx.dtype() instanceof DType.Primitive)) {
			throw new IllegalStateException("vortex.sparse: expected primitive dtype, got " + ctx.dtype());
		}
		PType valuePtype = ((DType.Primitive) ctx.dtype()).ptype();

		// Fill value from buffer[0]
		MemorySegment fillBuf = ctx.buffer(0);
		byte[] fillBytes = fillBuf.toArray(ValueLayout.JAVA_BYTE);
		ScalarProtos.ScalarValue fillScalar;
		try {
			fillScalar = ScalarProtos.ScalarValue.parseFrom(fillBytes);
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalStateException("vortex.sparse: invalid fill value", e);
		}

		long n = ctx.rowCount();

		// Allocate output buffer filled with the fill value
		int elemBytes = valuePtype.byteSize();
		MemorySegment out = ctx.arena().allocate(n * elemBytes);
		fillSegment(out, n, valuePtype, fillScalar);

		if (numPatches == 0) {
			return new Array(ctx.dtype(), n,
					new MemorySegment[]{out.asReadOnly()}, Array.NO_CHILDREN, ArrayStats.empty());
		}

		// Decode patch indices with their own dtype and rowCount
		DType indicesDtype = new DType.Primitive(indicesPtype, false);
		Array indicesArray = decodeChildWithDtype(ctx, 0, indicesDtype, numPatches);

		// Decode patch values with parent dtype and numPatches rowCount
		Array valuesArray = decodeChildWithDtype(ctx, 1, ctx.dtype(), numPatches);

		// Apply patches: output[index - offset] = value
		applyPatches(out, n, valuePtype,
				indicesArray.buffer(0), valuesArray.buffer(0), indicesPtype, numPatches, offset);

		return new Array(ctx.dtype(), n,
				new MemorySegment[]{out.asReadOnly()}, Array.NO_CHILDREN, ArrayStats.empty());
	}
}
