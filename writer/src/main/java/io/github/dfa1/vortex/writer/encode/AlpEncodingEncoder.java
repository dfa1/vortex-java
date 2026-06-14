package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.ALPMetadata;
import io.github.dfa1.vortex.proto.PatchesMetadata;
import io.github.dfa1.vortex.proto.ScalarValue;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for {@code vortex.alp}.
public final class AlpEncodingEncoder implements EncodingEncoder {
    private static final double[] F10_F64 = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22, 1e23};
    private static final double[] IF10_F64 = {1e-0, 1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6, 1e-7, 1e-8, 1e-9, 1e-10, 1e-11, 1e-12, 1e-13, 1e-14, 1e-15, 1e-16, 1e-17, 1e-18, 1e-19, 1e-20, 1e-21, 1e-22, 1e-23};
    private static final float[] F10_F32 = {1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f};
    private static final float[] IF10_F32 = {1e-0f, 1e-1f, 1e-2f, 1e-3f, 1e-4f, 1e-5f, 1e-6f, 1e-7f, 1e-8f, 1e-9f, 1e-10f};
    private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);
    private static final DType I32_DTYPE = new DType.Primitive(PType.I32, false);

    private static final int MAX_EXPONENT_F64 = 18;
    private static final int MAX_EXPONENT_F32 = 10;
    private static final int SAMPLE_SIZE = 512;

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public AlpEncodingEncoder() {
    }

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
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        return switch (ptype) {
            case F64 -> encodeF64((double[]) data, ctx);
            case F32 -> encodeF32((float[]) data, ctx);
            default -> throw new UnsupportedOperationException("ALP encode not supported for " + ptype);
        };
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        if (ptype == PType.F64) {
            return encodeCascadeF64((double[]) data, ctx);
        }
        return CascadeStep.terminal(encode(dtype, data, ctx));
    }

    private static int[] findExponentsF64(double[] values) {
        int n = values.length;
        int sampleLen = Math.min(SAMPLE_SIZE, n);
        // Stratified sample: pick SAMPLE_SIZE values spaced evenly across the input rather than
        // the leading prefix. Leading rows are often biased (sorted by timestamp / region) and
        // produce a bad (expE, expF) choice that inflates exceptions on the full data.
        double[] sample = new double[sampleLen];
        long stride = Math.max(1, (long) n / sampleLen);
        for (int i = 0; i < sampleLen; i++) {
            sample[i] = values[(int) Math.min(i * stride, n - 1)];
        }
        int bestExpE = 0, bestExpF = 0, bestExceptions = sampleLen + 1;

        outer:
        for (int expE = 0; expE <= MAX_EXPONENT_F64; expE++) {
            for (int expF = 0; expF <= MAX_EXPONENT_F64; expF++) {
                double ef = F10_F64[expE];
                double iff = IF10_F64[expF];
                double df = F10_F64[expF];
                double de = IF10_F64[expE];
                int exceptions = 0;
                for (int i = 0; i < sampleLen; i++) {
                    double enc = sample[i] * ef * iff;
                    if (!Double.isFinite(enc) || (double) Math.round(enc) * df * de != sample[i]) {
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
        double ef = F10_F64[expE];
        double iff = IF10_F64[expF];
        double df = F10_F64[expF];
        double de = IF10_F64[expE];

        long[] encodedArr = new long[n];
        var patchIndices = new ArrayList<Integer>();
        var patchValues = new ArrayList<Double>();

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double v = values[i];
            double enc = v * ef * iff;
            long encoded;
            if (Double.isFinite(enc) && (double) (encoded = Math.round(enc)) * df * de == v) {
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

    private static EncodeResult encodeF64(double[] values, EncodeContext ctx) {
        AlpF64Data d = computeF64(values);
        int n = values.length;

        MemorySegment encodedBuf = ctx.arena().allocate((long) n * 8, 8);
        for (int i = 0; i < n; i++) {
            encodedBuf.setAtIndex(PTypeIO.LE_LONG, i, d.encodedArr()[i]);
        }

        EncodeNode encodedNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);

        if (d.patchIndices().isEmpty()) {
            byte[] metaBytes = new ALPMetadata(d.expE(), d.expF(), null).encode();
            EncodeNode root = new EncodeNode(EncodingId.VORTEX_ALP,
                ByteBuffer.wrap(metaBytes), new EncodeNode[]{encodedNode}, new int[0]);
            return new EncodeResult(root, List.of(encodedBuf), d.statsMin(), d.statsMax());
        }

        int numPatches = d.patchIndices().size();
        MemorySegment idxBuf = ctx.arena().allocate((long) numPatches * 4, 4);
        MemorySegment valBuf = ctx.arena().allocate((long) numPatches * 8, 8);
        for (int i = 0; i < numPatches; i++) {
            idxBuf.setAtIndex(PTypeIO.LE_INT, i, d.patchIndices().get(i));
            valBuf.setAtIndex(PTypeIO.LE_DOUBLE, i, d.patchValues().get(i));
        }

        PatchesMetadata patches = buildPatchesMeta(numPatches);
        byte[] metaBytes = new ALPMetadata(d.expE(), d.expF(), patches).encode();

        EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ALP,
            ByteBuffer.wrap(metaBytes),
            new EncodeNode[]{encodedNode, idxNode, valNode},
            new int[0]);
        return new EncodeResult(root, List.of(encodedBuf, idxBuf, valBuf), d.statsMin(), d.statsMax());
    }

    private static CascadeStep encodeCascadeF64(double[] values, EncodeContext ctx) {
        AlpF64Data d = computeF64(values);
        if (d.patchIndices().isEmpty()) {
            byte[] metaBytes = new ALPMetadata(d.expE(), d.expF(), null).encode();
            EncodeNode partialRoot = new EncodeNode(EncodingId.VORTEX_ALP,
                ByteBuffer.wrap(metaBytes), new EncodeNode[1], new int[0]);
            ChildSlot slot = new ChildSlot(I64_DTYPE, d.encodedArr(), 0);
            return new CascadeStep(partialRoot, List.of(), List.of(slot), d.statsMin(), d.statsMax(), true);
        }

        int numPatches = d.patchIndices().size();
        MemorySegment idxBuf = ctx.arena().allocate((long) numPatches * 4, 4);
        MemorySegment valBuf = ctx.arena().allocate((long) numPatches * 8, 8);
        for (int i = 0; i < numPatches; i++) {
            idxBuf.setAtIndex(PTypeIO.LE_INT, i, d.patchIndices().get(i));
            valBuf.setAtIndex(PTypeIO.LE_DOUBLE, i, d.patchValues().get(i));
        }

        PatchesMetadata patches = buildPatchesMeta(numPatches);
        byte[] metaBytes = new ALPMetadata(d.expE(), d.expF(), patches).encode();

        EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode partialRoot = new EncodeNode(EncodingId.VORTEX_ALP,
            ByteBuffer.wrap(metaBytes), new EncodeNode[]{null, idxNode, valNode}, new int[0]);
        ChildSlot slot = new ChildSlot(I64_DTYPE, d.encodedArr(), 0);
        return new CascadeStep(partialRoot, List.of(idxBuf, valBuf), List.of(slot), d.statsMin(), d.statsMax(), true);
    }

    private static int[] findExponentsF32(float[] values) {
        int n = values.length;
        int sampleLen = Math.min(SAMPLE_SIZE, n);
        // Stratified sample: see findExponentsF64 for rationale.
        float[] sample = new float[sampleLen];
        long stride = Math.max(1, (long) n / sampleLen);
        for (int i = 0; i < sampleLen; i++) {
            sample[i] = values[(int) Math.min(i * stride, n - 1)];
        }
        int bestExpE = 0, bestExpF = 0, bestExceptions = sampleLen + 1;

        outer:
        for (int expE = 0; expE <= MAX_EXPONENT_F32; expE++) {
            for (int expF = 0; expF <= MAX_EXPONENT_F32; expF++) {
                float ef = F10_F32[expE];
                float iff = IF10_F32[expF];
                float df = F10_F32[expF];
                float de = IF10_F32[expE];
                int exceptions = 0;
                for (int i = 0; i < sampleLen; i++) {
                    float enc = sample[i] * ef * iff;
                    if (!Float.isFinite(enc) || (float) Math.round(enc) * df * de != sample[i]) {
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

    private static EncodeResult encodeF32(float[] values, EncodeContext ctx) {
        int n = values.length;
        int[] exps = findExponentsF32(values);
        int expE = exps[0], expF = exps[1];
        float ef = F10_F32[expE];
        float iff = IF10_F32[expF];
        float df = F10_F32[expF];
        float de = IF10_F32[expE];

        int[] encodedArr = new int[n];
        var patchIndices = new ArrayList<Integer>();
        var patchValues = new ArrayList<Float>();

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float v = values[i];
            float enc = v * ef * iff;
            int encoded;
            if (Float.isFinite(enc) && (float) (encoded = Math.round(enc)) * df * de == v) {
                encodedArr[i] = encoded;
            } else {
                encodedArr[i] = 0;
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

        byte[] statsMin = n > 0 ? scalarF32(min) : null;
        byte[] statsMax = n > 0 ? scalarF32(max) : null;

        MemorySegment encodedBuf = ctx.arena().allocate((long) n * 4, 4);
        for (int i = 0; i < n; i++) {
            encodedBuf.setAtIndex(PTypeIO.LE_INT, i, encodedArr[i]);
        }

        EncodeNode encodedNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);

        if (patchIndices.isEmpty()) {
            byte[] metaBytes = new ALPMetadata(expE, expF, null).encode();
            EncodeNode root = new EncodeNode(EncodingId.VORTEX_ALP,
                ByteBuffer.wrap(metaBytes), new EncodeNode[]{encodedNode}, new int[0]);
            return new EncodeResult(root, List.of(encodedBuf), statsMin, statsMax);
        }

        int numPatches = patchIndices.size();
        MemorySegment idxBuf = ctx.arena().allocate((long) numPatches * 4, 4);
        MemorySegment valBuf = ctx.arena().allocate((long) numPatches * 4, 4);
        for (int i = 0; i < numPatches; i++) {
            idxBuf.setAtIndex(PTypeIO.LE_INT, i, patchIndices.get(i));
            valBuf.setAtIndex(PTypeIO.LE_FLOAT, i, patchValues.get(i));
        }

        PatchesMetadata patches = new PatchesMetadata(
                numPatches,
                0L,
                io.github.dfa1.vortex.proto.PType.fromValue(PType.U32.ordinal()),
                null, null, null);
        byte[] metaBytes = new ALPMetadata(expE, expF, patches).encode();

        EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ALP,
            ByteBuffer.wrap(metaBytes),
            new EncodeNode[]{encodedNode, idxNode, valNode},
            new int[0]);
        return new EncodeResult(root, List.of(encodedBuf, idxBuf, valBuf), statsMin, statsMax);
    }

    private static PatchesMetadata buildPatchesMeta(int numPatches) {
        return new PatchesMetadata(
                numPatches,
                0L,
                io.github.dfa1.vortex.proto.PType.fromValue(PType.U32.ordinal()),
                null, null, null);
    }

    private static byte[] scalarF64(double v) {
        return ScalarValue.ofF64Value(v).encode();
    }

    private static byte[] scalarF32(float v) {
        return ScalarValue.ofF32Value(v).encode();
    }

    private record AlpF64Data(int expE, int expF, long[] encodedArr,
                              List<Integer> patchIndices, List<Double> patchValues,
                              byte[] statsMin, byte[] statsMax) {
    }
}
