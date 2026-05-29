package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.DTypeProtos;
import dev.vortex.proto.EncodingProtos;
import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/// Decoder for {@code vortex.alp} — Adaptive Lossless floating-Point compression.
///
/// <p>Metadata: protobuf {@code ALPMetadata} — {@code exp_e u32} (tag 1),
/// {@code exp_f u32} (tag 2), optional {@code patches PatchesMetadata} (tag 3).
///
/// <p>Child slot 0: encoded integers (I32 for F32, I64 for F64 parent), rowCount = array len.
/// Child slot 1: patch indices (if patches), rowCount = patches.len.
/// Child slot 2: patch values  (if patches, same dtype as parent), rowCount = patches.len.
/// Child slot 3: chunk offsets (optional, ignored — patches applied by absolute index).
///
/// <p>Decode: {@code decoded[i] = (float/double) encoded[i] * F10[exp_f] * IF10[exp_e]},
/// then overwrite {@code decoded[indices[j] - offset] = values[j]} for each patch.
public final class AlpCodec implements Codec {

	// Powers of 10 for F64 (index 0..18 used by the encoder).
	private static final double[] F10_F64 = {
			1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9,
			1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19,
			1e20, 1e21, 1e22, 1e23
	};

	private static final double[] IF10_F64 = {
			1e-0, 1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6, 1e-7, 1e-8, 1e-9,
			1e-10, 1e-11, 1e-12, 1e-13, 1e-14, 1e-15, 1e-16, 1e-17, 1e-18, 1e-19,
			1e-20, 1e-21, 1e-22, 1e-23
	};

	// Powers of 10 for F32.
	private static final float[] F10_F32 = {
			1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f
	};

	private static final float[] IF10_F32 = {
			1e-0f, 1e-1f, 1e-2f, 1e-3f, 1e-4f, 1e-5f, 1e-6f, 1e-7f, 1e-8f, 1e-9f, 1e-10f
	};

	private static final ValueLayout.OfShort  LE_SHORT  = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfLong   LE_LONG   = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfDouble LE_DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfInt    LE_INT    = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfFloat  LE_FLOAT  = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);
	private static final DType I32_DTYPE = new DType.Primitive(PType.I32, false);

	// Cascade: encoded I64s are delta-compressed to reduce storage size.
	private static final DeltaCodec DELTA_CODEC = new DeltaCodec();

	private static Array decodeChildAs(DecodeContext parent, int childIdx, DType dtype, long rowCount) {
		ArrayNode childNode = parent.node().children()[childIdx];
		DecodeContext childCtx = new DecodeContext(
				childNode, dtype, rowCount, parent.segmentBuffers(), parent.registry(), parent.arena());
		return parent.registry().decode(childCtx);
	}

	private static long readUnsigned(MemorySegment seg, long i, PType ptype) {
		return switch (ptype) {
			case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
			case U16 -> Short.toUnsignedLong(seg.get(LE_SHORT, i * 2));
			case U32 -> Integer.toUnsignedLong(seg.get(LE_INT, i * 4));
			case U64 -> seg.get(LE_LONG, i * 8);
			default -> throw new VortexException(CodecId.VORTEX_ALP, "non-unsigned patch index ptype " + ptype);
		};
	}

	// ── F64 ───────────────────────────────────────────────────────────────────

	private static PType ptypeFromProto(DTypeProtos.PType proto) {
		return PType.values()[proto.getNumber()];
	}

	private static final int SAMPLE_SIZE = 512;

	@Override
	public CodecId encodingId() {
		return CodecId.VORTEX_ALP;
	}

	@Override
	public boolean accepts(DType dtype) {
		if (!(dtype instanceof DType.Primitive p)) {
			return false;
		}
		return p.ptype() == PType.F64 || p.ptype() == PType.F32;
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		PType ptype = ((DType.Primitive) dtype).ptype();
		if (ptype == PType.F64) {
			return encodeF64((double[]) data, dtype);
		}
		throw new UnsupportedOperationException("ALP encode not yet supported for " + ptype);
	}

	// ── F64 encode ────────────────────────────────────────────────────────────

	private static int[] findExponentsF64(double[] values) {
		int sampleLen = Math.min(SAMPLE_SIZE, values.length);
		int bestExpE = 0, bestExpF = 0, bestExceptions = sampleLen + 1;

		outer:
		for (int expE = 0; expE < F10_F64.length; expE++) {
			for (int expF = 0; expF < F10_F64.length; expF++) {
				double encFactor = F10_F64[expE] * IF10_F64[expF];
				int exceptions = 0;
				for (int i = 0; i < sampleLen; i++) {
					double enc = values[i] * encFactor;
					// Use division for the roundtrip check: x*(a*b) ≠ x/a in IEEE 754
					// when a is not a power of 2, so decoded = encoded/encFactor gives
					// the correct result for values generated as integers divided by a power of 10.
					if (!Double.isFinite(enc) || (double) Math.round(enc) / encFactor != values[i]) {
						exceptions++;
					}
				}
				if (exceptions < bestExceptions) {
					bestExceptions = exceptions;
					bestExpE = expE;
					bestExpF = expF;
					if (bestExceptions == 0) {
						break outer;
					}
				}
			}
		}
		return new int[]{bestExpE, bestExpF};
	}

	private static EncodeResult encodeF64(double[] values, DType dtype) {
		int n = values.length;
		int[] exps = findExponentsF64(values);
		int expE = exps[0], expF = exps[1];
		double encFactor = F10_F64[expE] * IF10_F64[expF];

		long[] encodedArr = new long[n];
		var patchIndices = new ArrayList<Integer>();
		var patchValues  = new ArrayList<Double>();

		double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			double v = values[i];
			double enc = v * encFactor;
			long encoded;
			if (Double.isFinite(enc) && (double) (encoded = Math.round(enc)) / encFactor == v) {
				encodedArr[i] = encoded;
			} else {
				encodedArr[i] = 0L;
				patchIndices.add(i);
				patchValues.add(v);
			}
			if (v < min) {
				min = v;
			}
			if (v > max) {
				max = v;
			}
		}

		byte[] statsMin = n > 0 ? scalarF64(min) : null;
		byte[] statsMax = n > 0 ? scalarF64(max) : null;

		// Cascade: delta-compress the ALP-encoded I64s to reduce storage size.
		EncodeResult deltaResult = DELTA_CODEC.encode(I64_DTYPE, encodedArr);

		if (patchIndices.isEmpty()) {
			byte[] metaBytes = EncodingProtos.ALPMetadata.newBuilder()
					.setExpE(expE).setExpF(expF).build().toByteArray();
			EncodeNode root = new EncodeNode(CodecId.VORTEX_ALP,
					ByteBuffer.wrap(metaBytes), new EncodeNode[]{deltaResult.rootNode()}, new int[0]);
			return new EncodeResult(root, deltaResult.buffers(), statsMin, statsMax);
		}

		// Patch buffers follow the delta buffer(s) in the combined list.
		int numPatches = patchIndices.size();
		int patchIdxBufOffset = deltaResult.buffers().size();
		int patchValBufOffset = patchIdxBufOffset + 1;

		ByteBuffer idxBuf = ByteBuffer.allocate(numPatches * 4).order(ByteOrder.LITTLE_ENDIAN);
		ByteBuffer valBuf = ByteBuffer.allocate(numPatches * 8).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < numPatches; i++) {
			idxBuf.putInt(patchIndices.get(i));
			valBuf.putDouble(patchValues.get(i));
		}
		idxBuf.flip();
		valBuf.flip();

		EncodingProtos.PatchesMetadata patches = EncodingProtos.PatchesMetadata.newBuilder()
				.setLen(numPatches)
				.setOffset(0)
				.setIndicesPtype(DTypeProtos.PType.forNumber(PType.U32.ordinal()))
				.build();
		byte[] metaBytes = EncodingProtos.ALPMetadata.newBuilder()
				.setExpE(expE).setExpF(expF).setPatches(patches).build().toByteArray();

		EncodeNode idxNode = EncodeNode.leaf(CodecId.VORTEX_PRIMITIVE, patchIdxBufOffset);
		EncodeNode valNode = EncodeNode.leaf(CodecId.VORTEX_PRIMITIVE, patchValBufOffset);
		EncodeNode root = new EncodeNode(CodecId.VORTEX_ALP,
				ByteBuffer.wrap(metaBytes),
				new EncodeNode[]{deltaResult.rootNode(), idxNode, valNode},
				new int[0]);

		var combinedBuffers = new ArrayList<>(deltaResult.buffers());
		combinedBuffers.add(idxBuf);
		combinedBuffers.add(valBuf);
		return new EncodeResult(root, combinedBuffers, statsMin, statsMax);
	}

	private static byte[] scalarF64(double v) {
		return ScalarProtos.ScalarValue.newBuilder().setF64Value(v).build().toByteArray();
	}

	@Override
	public Array decode(DecodeContext ctx) {
		ByteBuffer rawMeta = ctx.metadata();
		// Proto3 omits fields equal to their default value (0), so ALPMetadata{expE=0,expF=0}
		// serialises to 0 bytes; treat null/empty metadata as the all-zero default instance.
		EncodingProtos.ALPMetadata meta;
		if (rawMeta == null || !rawMeta.hasRemaining()) {
			meta = EncodingProtos.ALPMetadata.getDefaultInstance();
		} else {
			try {
				meta = EncodingProtos.ALPMetadata.parseFrom(rawMeta.duplicate());
			} catch (InvalidProtocolBufferException e) {
				throw new VortexException(CodecId.VORTEX_ALP, "invalid metadata", e);
			}
		}

		if (!(ctx.dtype() instanceof DType.Primitive p)) {
			throw new VortexException(CodecId.VORTEX_ALP, "expected primitive dtype, got " + ctx.dtype());
		}

		int expE = meta.getExpE();
		int expF = meta.getExpF();
		PType ptype = p.ptype();
		long n = ctx.rowCount();

		return switch (ptype) {
			case F64 -> decodeF64(ctx, meta, expE, expF, n);
			case F32 -> decodeF32(ctx, meta, expE, expF, n);
			default -> throw new VortexException(CodecId.VORTEX_ALP, "unsupported dtype " + ptype);
		};
	}

	private Array decodeF64(DecodeContext ctx, EncodingProtos.ALPMetadata meta, int expE, int expF, long n) {
		Array encoded = decodeChildAs(ctx, 0, I64_DTYPE, n);

		// Precompute single factor — avoids 2 FP mults per element in the hot loop.
		double factor = F10_F64[expF] * IF10_F64[expE];

		MemorySegment src = encoded.buffer(0);
		MemorySegment out = ctx.arena().allocate(n * 8, 8);
		for (long i = 0; i < n; i++) {
			out.setAtIndex(LE_DOUBLE, i, (double) src.getAtIndex(LE_LONG, i) * factor);
		}

		if (meta.hasPatches()) {
			applyPatches(ctx, meta.getPatches(), out, LE_LONG, 8);
		}

		return new DoubleArray(ctx.dtype(), n, out.asReadOnly(), ArrayStats.empty());
	}

	private Array decodeF32(DecodeContext ctx, EncodingProtos.ALPMetadata meta, int expE, int expF, long n) {
		Array encoded = decodeChildAs(ctx, 0, I32_DTYPE, n);

		// Precompute single factor — avoids 2 FP mults per element in the hot loop.
		float factor = F10_F32[expF] * IF10_F32[expE];

		MemorySegment src = encoded.buffer(0);
		MemorySegment out = ctx.arena().allocate(n * 4, 4);
		for (long i = 0; i < n; i++) {
			out.setAtIndex(LE_FLOAT, i, (float) src.getAtIndex(LE_INT, i) * factor);
		}

		if (meta.hasPatches()) {
			applyPatches(ctx, meta.getPatches(), out, LE_INT, 4);
		}

		return new FloatArray(ctx.dtype(), n, out.asReadOnly(), ArrayStats.empty());
	}

	private void applyPatches(DecodeContext ctx, EncodingProtos.PatchesMetadata pm,
	                          MemorySegment out, ValueLayout elemLayout, int elemBytes) {
		long numPatches = pm.getLen();
		long offset = pm.getOffset();
		PType idxPtype = ptypeFromProto(pm.getIndicesPtype());

		Array idxArr = decodeChildAs(ctx, 1, new DType.Primitive(idxPtype, false), numPatches);
		Array valArr = decodeChildAs(ctx, 2, ctx.dtype(), numPatches);

		MemorySegment idxSeg = idxArr.buffer(0);
		MemorySegment valSeg = valArr.buffer(0);

		for (long i = 0; i < numPatches; i++) {
			long absIdx = readUnsigned(idxSeg, i, idxPtype) - offset;
			MemorySegment.copy(valSeg, i * elemBytes, out, absIdx * elemBytes, elemBytes);
		}
	}
}
