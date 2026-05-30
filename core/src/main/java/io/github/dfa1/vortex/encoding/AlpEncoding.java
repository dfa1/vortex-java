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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

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
public final class AlpEncoding implements Encoding {

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

	private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);
	private static final DType I32_DTYPE = new DType.Primitive(PType.I32, false);

	private static Array decodeChildAs(DecodeContext parent, int childIdx, DType dtype, long rowCount) {
		ArrayNode childNode = parent.node().children()[childIdx];
		DecodeContext childCtx = new DecodeContext(
				childNode, dtype, rowCount, parent.segmentBuffers(), parent.registry(), parent.arena());
		return parent.registry().decode(childCtx);
	}

	private static long readUnsigned(MemorySegment seg, long i, PType ptype) {
		return switch (ptype) {
			case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
			case U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, i * 2));
			case U32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, i * 4));
			case U64 -> seg.get(PTypeIO.LE_LONG, i * 8);
			default -> throw new VortexException(EncodingId.VORTEX_ALP, "non-unsigned patch index ptype " + ptype);
		};
	}

	// ── F64 ───────────────────────────────────────────────────────────────────

	private static PType ptypeFromProto(DTypeProtos.PType proto) {
		return PType.values()[proto.getNumber()];
	}

	private static final int SAMPLE_SIZE = 512;

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_ALP;
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

	@Override
	public CascadeStep encodeCascade(DType dtype, Object data, CompressorContext ctx) {
		PType ptype = ((DType.Primitive) dtype).ptype();
		if (ptype == PType.F64) {
			return encodeCascadeF64((double[]) data);
		}
		return CascadeStep.terminal(encode(dtype, data));
	}

	// ── F64 encode ────────────────────────────────────────────────────────────

	private record AlpF64Data(int expE, int expF, long[] encodedArr,
	                          List<Integer> patchIndices, List<Double> patchValues,
	                          byte[] statsMin, byte[] statsMax) {}

	private static int[] findExponentsF64(double[] values) {
		int sampleLen = Math.min(SAMPLE_SIZE, values.length);
		int bestExpE = 0, bestExpF = 0, bestExceptions = sampleLen + 1;

		outer:
		for (int expE = 0; expE < F10_F64.length; expE++) {
			for (int expF = 0; expF < F10_F64.length; expF++) {
				double encFactor = F10_F64[expE] * IF10_F64[expF];
				double decFactor = F10_F64[expF] * IF10_F64[expE];
				int exceptions = 0;
				for (int i = 0; i < sampleLen; i++) {
					double enc = values[i] * encFactor;
					if (!Double.isFinite(enc) || (double) Math.round(enc) * decFactor != values[i]) {
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

	private static AlpF64Data computeF64(double[] values) {
		int n = values.length;
		int[] exps = findExponentsF64(values);
		int expE = exps[0], expF = exps[1];
		double encFactor = F10_F64[expE] * IF10_F64[expF];
		double decFactor = F10_F64[expF] * IF10_F64[expE];

		long[] encodedArr = new long[n];
		var patchIndices = new ArrayList<Integer>();
		var patchValues  = new ArrayList<Double>();

		double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			double v = values[i];
			double enc = v * encFactor;
			long encoded;
			if (Double.isFinite(enc) && (double) (encoded = Math.round(enc)) * decFactor == v) {
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
		return new AlpF64Data(expE, expF, encodedArr, patchIndices, patchValues, statsMin, statsMax);
	}

	private static EncodeResult encodeF64(double[] values, DType dtype) {
		AlpF64Data d = computeF64(values);
		int n = values.length;

		MemorySegment encodedBuf = Arena.ofAuto().allocate((long) n * 8, 8);
		for (int i = 0; i < n; i++) {
			encodedBuf.setAtIndex(PTypeIO.LE_LONG, i, d.encodedArr()[i]);
		}

		EncodeNode encodedNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);

		if (d.patchIndices().isEmpty()) {
			byte[] metaBytes = EncodingProtos.ALPMetadata.newBuilder()
					.setExpE(d.expE()).setExpF(d.expF()).build().toByteArray();
			EncodeNode root = new EncodeNode(EncodingId.VORTEX_ALP,
					ByteBuffer.wrap(metaBytes), new EncodeNode[]{encodedNode}, new int[0]);
			return new EncodeResult(root, List.of(encodedBuf), d.statsMin(), d.statsMax());
		}

		int numPatches = d.patchIndices().size();
		MemorySegment idxBuf = Arena.ofAuto().allocate((long) numPatches * 4, 4);
		MemorySegment valBuf = Arena.ofAuto().allocate((long) numPatches * 8, 8);
		for (int i = 0; i < numPatches; i++) {
			idxBuf.setAtIndex(PTypeIO.LE_INT, i, d.patchIndices().get(i));
			valBuf.setAtIndex(PTypeIO.LE_DOUBLE, i, d.patchValues().get(i));
		}

		EncodingProtos.PatchesMetadata patches = buildPatchesMeta(numPatches);
		byte[] metaBytes = EncodingProtos.ALPMetadata.newBuilder()
				.setExpE(d.expE()).setExpF(d.expF()).setPatches(patches).build().toByteArray();

		EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
		EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
		EncodeNode root = new EncodeNode(EncodingId.VORTEX_ALP,
				ByteBuffer.wrap(metaBytes),
				new EncodeNode[]{encodedNode, idxNode, valNode},
				new int[0]);
		return new EncodeResult(root, List.of(encodedBuf, idxBuf, valBuf), d.statsMin(), d.statsMax());
	}

	/// Cascade-aware F64 encode: I64 child is an open ChildSlot so the compressor
	/// can bitpack it. Patch idx/val buffers (if any) stay in ownedBuffers.
	private static CascadeStep encodeCascadeF64(double[] values) {
		AlpF64Data d = computeF64(values);
		int n = values.length;

		if (d.patchIndices().isEmpty()) {
			byte[] metaBytes = EncodingProtos.ALPMetadata.newBuilder()
					.setExpE(d.expE()).setExpF(d.expF()).build().toByteArray();
			// children[0] will be filled by the compressor after recursing on the ChildSlot
			EncodeNode partialRoot = new EncodeNode(EncodingId.VORTEX_ALP,
					ByteBuffer.wrap(metaBytes), new EncodeNode[1], new int[0]);
			ChildSlot slot = new ChildSlot(I64_DTYPE, d.encodedArr(), 0);
			return new CascadeStep(partialRoot, List.of(), List.of(slot), d.statsMin(), d.statsMax());
		}

		int numPatches = d.patchIndices().size();
		MemorySegment idxBuf = Arena.ofAuto().allocate((long) numPatches * 4, 4);
		MemorySegment valBuf = Arena.ofAuto().allocate((long) numPatches * 8, 8);
		for (int i = 0; i < numPatches; i++) {
			idxBuf.setAtIndex(PTypeIO.LE_INT, i, d.patchIndices().get(i));
			valBuf.setAtIndex(PTypeIO.LE_DOUBLE, i, d.patchValues().get(i));
		}

		EncodingProtos.PatchesMetadata patches = buildPatchesMeta(numPatches);
		byte[] metaBytes = EncodingProtos.ALPMetadata.newBuilder()
				.setExpE(d.expE()).setExpF(d.expF()).setPatches(patches).build().toByteArray();

		// ownedBuffers[0]=idxBuf, [1]=valBuf; child I64 will be appended after (starting at idx 2)
		EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
		EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
		EncodeNode partialRoot = new EncodeNode(EncodingId.VORTEX_ALP,
				ByteBuffer.wrap(metaBytes), new EncodeNode[]{null, idxNode, valNode}, new int[0]);
		ChildSlot slot = new ChildSlot(I64_DTYPE, d.encodedArr(), 0);
		return new CascadeStep(partialRoot, List.of(idxBuf, valBuf), List.of(slot), d.statsMin(), d.statsMax());
	}

	private static EncodingProtos.PatchesMetadata buildPatchesMeta(int numPatches) {
		return EncodingProtos.PatchesMetadata.newBuilder()
				.setLen(numPatches)
				.setOffset(0)
				.setIndicesPtype(DTypeProtos.PType.forNumber(PType.U32.ordinal()))
				.build();
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
				throw new VortexException(EncodingId.VORTEX_ALP, "invalid metadata", e);
			}
		}

		if (!(ctx.dtype() instanceof DType.Primitive p)) {
			throw new VortexException(EncodingId.VORTEX_ALP, "expected primitive dtype, got " + ctx.dtype());
		}

		int expE = meta.getExpE();
		int expF = meta.getExpF();
		PType ptype = p.ptype();
		long n = ctx.rowCount();

		return switch (ptype) {
			case F64 -> decodeF64(ctx, meta, expE, expF, n);
			case F32 -> decodeF32(ctx, meta, expE, expF, n);
			default -> throw new VortexException(EncodingId.VORTEX_ALP, "unsupported dtype " + ptype);
		};
	}

	private Array decodeF64(DecodeContext ctx, EncodingProtos.ALPMetadata meta, int expE, int expF, long n) {
		Array encoded = decodeChildAs(ctx, 0, I64_DTYPE, n);

		// Precompute single factor — avoids 2 FP mults per element in the hot loop.
		double factor = F10_F64[expF] * IF10_F64[expE];

		MemorySegment src = encoded.buffer(0);
		// In-place when the child returned a writable arena buffer (e.g. BitpackedEncoding, DeltaEncoding).
		// Fall back to a new allocation when the source is a read-only mmap slice (PrimitiveEncoding).
		MemorySegment buf = src.isReadOnly() ? ctx.arena().allocate(n * 8, 8) : src;
		if (src.isReadOnly()) {
			for (long i = 0; i < n; i++) {
				buf.setAtIndex(PTypeIO.LE_DOUBLE, i, (double) src.getAtIndex(PTypeIO.LE_LONG, i) * factor);
			}
		} else {
			for (long i = 0; i < n; i++) {
				buf.setAtIndex(PTypeIO.LE_DOUBLE, i, (double) buf.getAtIndex(PTypeIO.LE_LONG, i) * factor);
			}
		}

		if (meta.hasPatches()) {
			applyPatches(ctx, meta.getPatches(), buf, PTypeIO.LE_LONG, 8);
		}

		return new DoubleArray(ctx.dtype(), n, buf.asReadOnly(), ArrayStats.empty());
	}

	private Array decodeF32(DecodeContext ctx, EncodingProtos.ALPMetadata meta, int expE, int expF, long n) {
		Array encoded = decodeChildAs(ctx, 0, I32_DTYPE, n);

		// Precompute single factor — avoids 2 FP mults per element in the hot loop.
		float factor = F10_F32[expF] * IF10_F32[expE];

		MemorySegment src32 = encoded.buffer(0);
		MemorySegment buf32 = src32.isReadOnly() ? ctx.arena().allocate(n * 4, 4) : src32;
		if (src32.isReadOnly()) {
			for (long i = 0; i < n; i++) {
				buf32.setAtIndex(PTypeIO.LE_FLOAT, i, (float) src32.getAtIndex(PTypeIO.LE_INT, i) * factor);
			}
		} else {
			for (long i = 0; i < n; i++) {
				buf32.setAtIndex(PTypeIO.LE_FLOAT, i, (float) buf32.getAtIndex(PTypeIO.LE_INT, i) * factor);
			}
		}

		if (meta.hasPatches()) {
			applyPatches(ctx, meta.getPatches(), buf32, PTypeIO.LE_INT, 4);
		}

		return new FloatArray(ctx.dtype(), n, buf32.asReadOnly(), ArrayStats.empty());
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
