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

/// Write-only encoder for `vortex.alp`.
public final class AlpEncodingEncoder implements EncodingEncoder {
    private static final double[] F10_F64 = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22, 1e23};
    private static final double[] IF10_F64 = {1e-0, 1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6, 1e-7, 1e-8, 1e-9, 1e-10, 1e-11, 1e-12, 1e-13, 1e-14, 1e-15, 1e-16, 1e-17, 1e-18, 1e-19, 1e-20, 1e-21, 1e-22, 1e-23};
    private static final float[] F10_F32 = {1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f};
    private static final float[] IF10_F32 = {1e-0f, 1e-1f, 1e-2f, 1e-3f, 1e-4f, 1e-5f, 1e-6f, 1e-7f, 1e-8f, 1e-9f, 1e-10f};
    private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);
    private static final DType I32_DTYPE = new DType.Primitive(PType.I32, false);

    private static final int MAX_EXPONENT_F64 = 18;
    private static final int MAX_EXPONENT_F32 = 10;
    // Wider than Rust's SAMPLE_SIZE=32: at small samples, IEEE precision drift at high
    // `(expE, expF)` can hide as a 0-patch tie in the size estimate, then explode into
    // thousands of patches when the full chunk is encoded. A larger sample is more likely to
    // include drift-triggering values, letting the search penalise such combinations correctly.
    private static final int SAMPLE_SIZE = 512;

    /// Public no-arg constructor required by [java.util.ServiceLoader].
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

    /// Picks `(expE, expF)` by minimising the estimated post-cascade byte size
    /// (FoR + bitpack on the encoded integers, plus per-exception patch overhead) on a
    /// stratified sample, breaking ties in favour of the smaller `e - f` gap.
    /// Mirrors Rust's `ALPFloat::find_best_exponents`.
    ///
    /// The previous heuristic (minimise exception count) picked combinations like
    /// `(e=14, f=0)` that produced few exceptions but huge encoded mantissas, forcing
    /// the cascade into Dict+FoR+BitPacked instead of a clean ALP→BitPacked chain.
    private static int[] findExponentsF64(double[] values) {
        int n = values.length;
        int sampleLen = Math.min(SAMPLE_SIZE, n);
        double[] sample = new double[sampleLen];
        long stride = Math.max(1, (long) n / sampleLen);
        for (int i = 0; i < sampleLen; i++) {
            sample[i] = values[(int) Math.min(i * stride, (long) n - 1)];
        }

        int bestExpE = 0;
        int bestExpF = 0;
        long bestSize = Long.MAX_VALUE;
        long[] encoded = new long[sampleLen];

        // Iterate e ascending and f ascending so the first-encountered minimum has the smallest
        // e (and smallest e-f for that e). With strict less-than replacement, ties resolve to
        // the smaller exponent — important because IEEE precision of `F10[f] * IF10[e]`
        // tends to drift at high (e, f), and Rust's sample-of-32 sometimes detects this drift
        // as exceptions while ours does not (sample bias). Preferring smaller e protects us.
        for (int expE = 1; expE < MAX_EXPONENT_F64; expE++) {
            for (int expF = 0; expF < expE; expF++) {
                long size = estimateEncodedSizeF64(sample, expE, expF, encoded);
                if (size < bestSize) {
                    bestSize = size;
                    bestExpE = expE;
                    bestExpF = expF;
                }
            }
        }
        return new int[]{bestExpE, bestExpF};
    }

    /// Estimates the post-cascade byte cost of encoding `sample` at `(expE, expF)`.
    /// Cost model matches Rust: encoded = FoR + bitpack at `ceil(log2(range)+1)` bits/value,
    /// plus `patchCount * (8 bytes value + 2 bytes index)` for exceptions.
    ///
    /// Encoded byte count is computed over the full sample length (not just the cleanly encoded
    /// values) to mirror Rust's `estimate_encoded_size`: patch positions are filled with the
    /// first non-patched encoded value, so they still consume bitpacked space.
    private static long estimateEncodedSizeF64(double[] sample, int expE, int expF, long[] encoded) {
        double ef = F10_F64[expE];
        double iff = IF10_F64[expF];
        double df = F10_F64[expF];
        double de = IF10_F64[expE];
        long minEnc = Long.MAX_VALUE;
        long maxEnc = Long.MIN_VALUE;
        int patchCount = 0;
        int encodedCount = 0;
        for (double v : sample) {
            double enc = v * ef * iff;
            if (!Double.isFinite(enc)) {
                patchCount++;
                continue;
            }
            long e = Math.round(enc);
            if ((double) e * df * de != v) {
                patchCount++;
                continue;
            }
            encoded[encodedCount++] = e;
            if (e < minEnc) {
                minEnc = e;
            }
            if (e > maxEnc) {
                maxEnc = e;
            }
        }
        int bitsPerEncoded;
        if (encodedCount == 0) {
            bitsPerEncoded = 64;
        } else {
            long range = maxEnc - minEnc;
            bitsPerEncoded = range <= 0 ? 0 : (64 - Long.numberOfLeadingZeros(range));
        }
        long encodedBytes = ((long) sample.length * bitsPerEncoded + 7L) / 8L;
        long patchBytes = (long) patchCount * (Double.BYTES + Short.BYTES);
        return encodedBytes + patchBytes;
    }

    private static AlpF64Data computeF64(double[] values) {
        int n = values.length;
        int[] exps = findExponentsF64(values);
        int expE = exps[0];
        int expF = exps[1];
        double ef = F10_F64[expE];
        double iff = IF10_F64[expF];
        double df = F10_F64[expF];
        double de = IF10_F64[expE];

        long[] encodedArr = new long[n];
        var patchIndices = new ArrayList<Integer>();
        var patchValues = new ArrayList<Double>();

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
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

    /// Size-based exponent search for F32. See [#findExponentsF64(double[])] for cost model.
    private static int[] findExponentsF32(float[] values) {
        int n = values.length;
        int sampleLen = Math.min(SAMPLE_SIZE, n);
        float[] sample = new float[sampleLen];
        long stride = Math.max(1, (long) n / sampleLen);
        for (int i = 0; i < sampleLen; i++) {
            sample[i] = values[(int) Math.min(i * stride, (long) n - 1)];
        }

        int bestExpE = 0;
        int bestExpF = 0;
        long bestSize = Long.MAX_VALUE;
        int[] encoded = new int[sampleLen];

        for (int expE = 1; expE < MAX_EXPONENT_F32; expE++) {
            for (int expF = 0; expF < expE; expF++) {
                long size = estimateEncodedSizeF32(sample, expE, expF, encoded);
                if (size < bestSize) {
                    bestSize = size;
                    bestExpE = expE;
                    bestExpF = expF;
                }
            }
        }
        return new int[]{bestExpE, bestExpF};
    }

    private static long estimateEncodedSizeF32(float[] sample, int expE, int expF, int[] encoded) {
        float ef = F10_F32[expE];
        float iff = IF10_F32[expF];
        float df = F10_F32[expF];
        float de = IF10_F32[expE];
        int minEnc = Integer.MAX_VALUE;
        int maxEnc = Integer.MIN_VALUE;
        int patchCount = 0;
        int encodedCount = 0;
        for (float v : sample) {
            float enc = v * ef * iff;
            if (!Float.isFinite(enc)) {
                patchCount++;
                continue;
            }
            int e = Math.round(enc);
            if ((float) e * df * de != v) {
                patchCount++;
                continue;
            }
            encoded[encodedCount++] = e;
            if (e < minEnc) {
                minEnc = e;
            }
            if (e > maxEnc) {
                maxEnc = e;
            }
        }
        int bitsPerEncoded;
        if (encodedCount == 0) {
            bitsPerEncoded = 32;
        } else {
            int range = maxEnc - minEnc;
            bitsPerEncoded = range <= 0 ? 0 : (32 - Integer.numberOfLeadingZeros(range));
        }
        long encodedBytes = ((long) sample.length * bitsPerEncoded + 7L) / 8L;
        long patchBytes = (long) patchCount * (Float.BYTES + Short.BYTES);
        return encodedBytes + patchBytes;
    }

    private static EncodeResult encodeF32(float[] values, EncodeContext ctx) {
        int n = values.length;
        int[] exps = findExponentsF32(values);
        int expE = exps[0];
        int expF = exps[1];
        float ef = F10_F32[expE];
        float iff = IF10_F32[expF];
        float df = F10_F32[expF];
        float de = IF10_F32[expE];

        int[] encodedArr = new int[n];
        var patchIndices = new ArrayList<Integer>();
        var patchValues = new ArrayList<Float>();

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
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

    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record AlpF64Data(int expE, int expF, long[] encodedArr,
                              List<Integer> patchIndices, List<Double> patchValues,
                              byte[] statsMin, byte[] statsMax) {
    }
}
