package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoALPMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

import java.lang.foreign.MemorySegment;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.alp`.
public final class AlpEncodingEncoder implements EncodingEncoder {
    private static final double[] F10_F64 = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22, 1e23};
    private static final double[] IF10_F64 = {1e-0, 1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6, 1e-7, 1e-8, 1e-9, 1e-10, 1e-11, 1e-12, 1e-13, 1e-14, 1e-15, 1e-16, 1e-17, 1e-18, 1e-19, 1e-20, 1e-21, 1e-22, 1e-23};
    private static final float[] F10_F32 = {1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f};
    private static final float[] IF10_F32 = {1e-0f, 1e-1f, 1e-2f, 1e-3f, 1e-4f, 1e-5f, 1e-6f, 1e-7f, 1e-8f, 1e-9f, 1e-10f};

    private static final int MAX_EXPONENT_F64 = 18;
    private static final int MAX_EXPONENT_F32 = 10;

    /// Rust's `fast_round` constant, `2^52 + 2^51`: `(x + SWEET) - SWEET` rounds `x` to the nearest
    /// integer (ties to even) in two IEEE additions, which Java evaluates bit-for-bit as Rust does.
    private static final double SWEET_F64 = (double) (1L << 52) + (double) (1L << 51);
    /// `2^23 + 2^22`, the f32 counterpart of [#SWEET_F64].
    private static final float SWEET_F32 = (float) (1 << 23) + (float) (1 << 22);

    /// The exponent search runs on at most this many values, `SAMPLE_BLOCK`-long runs spread
    /// evenly over the array: Rust's `alp` crate (0.0.4, as vortex 0.86.1 pins it) `SAMPLE_SIZE`.
    /// The search is the dominant cost of every ALP encode and trial encode, so its sample size
    /// sets ALP's cost.
    private static final int SAMPLE_SIZE = 64;
    /// Rust's `SAMPLE_BLOCK`.
    private static final int SAMPLE_BLOCK = 8;

    /// Bytes an exception costs in the size estimate: the value plus a `u16` position (Rust's
    /// `patch_bytes`).
    private static final int PATCH_BYTES_F64 = Double.BYTES + Short.BYTES;
    private static final int PATCH_BYTES_F32 = Float.BYTES + Short.BYTES;

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ALP;
    }

    /// Rust's `ALPScheme` verdict (`vortex-btrblocks` `schemes/float/alp.rs`): only worth its
    /// cascaded integer child, so skip once cascading is finished; else defer to the sample.
    @Override
    public Estimate expectedRatio(DType dtype, ArrayAndStats data, EncodeContext ctx) {
        return ctx.finishedCascading() ? Estimate.SKIP : Estimate.COMPLETE;
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

    // Rust's `encode_single_unchecked`: `(value * 10^e * 10^-f).fast_round() as i64`, the cast
    // saturating (and NaN -> 0) exactly as Rust's `as` does.
    private static long encodeF64(double v, int e, int f) {
        return (long) ((v * F10_F64[e] * IF10_F64[f] + SWEET_F64) - SWEET_F64);
    }

    // Rust's `decode_single`: `encoded as f64 * 10^f * 10^-e`.
    private static double decodeF64(long encoded, int e, int f) {
        return encoded * F10_F64[f] * IF10_F64[e];
    }

    /// Rust's `SamplePlan::subsample`: the whole array when it is short, else
    /// `SAMPLE_SIZE / SAMPLE_BLOCK` runs of `SAMPLE_BLOCK` values, evenly spaced from the start
    /// to the end.
    private static double[] sampleF64(double[] values) {
        int n = values.length;
        if (n <= SAMPLE_SIZE) {
            return values;
        }
        int blocks = SAMPLE_SIZE / SAMPLE_BLOCK;
        int spacing = (n - SAMPLE_BLOCK) / (blocks - 1);
        double[] sample = new double[blocks * SAMPLE_BLOCK];
        for (int i = 0; i < blocks; i++) {
            System.arraycopy(values, i * spacing, sample, i * SAMPLE_BLOCK, SAMPLE_BLOCK);
        }
        return sample;
    }

    /// Rust's `find_best_exponents` (`alp` 0.0.4): every `f <= e`, `e` descending, scored by
    /// [#estimateEncodedSizeF64]; a tie goes to the smaller `e - f`.
    private static int[] findExponentsF64(double[] values) {
        double[] sample = sampleF64(values);
        int bestE = 0;
        int bestF = 0;
        long bestSize = estimateEncodedSizeF64(sample, 0, 0, Long.MAX_VALUE);
        for (int e = MAX_EXPONENT_F64 - 1; e >= 0; e--) {
            for (int f = 0; f <= e; f++) {
                long size = estimateEncodedSizeF64(sample, e, f, bestSize);
                if (size >= 0 && (size < bestSize || size == bestSize && e - f < bestE - bestF)) {
                    bestSize = size;
                    bestE = e;
                    bestF = f;
                }
            }
        }
        return new int[]{bestE, bestF};
    }

    /// Rust's `estimate_encoded_size_within`: the encoded values frame-of-referenced and bit-packed
    /// at the width of the round-tripping values' range (all values' when none round-trips), plus
    /// [#PATCH_BYTES_F64] per exception.
    ///
    /// @return the estimate, or `-1` once the exceptions alone cost more than `limit`
    private static long estimateEncodedSizeF64(double[] values, int e, int f, long limit) {
        long keptMin = Long.MAX_VALUE;
        long keptMax = Long.MIN_VALUE;
        long allMin = Long.MAX_VALUE;
        long allMax = Long.MIN_VALUE;
        int patches = 0;
        for (double v : values) {
            long encoded = encodeF64(v, e, f);
            allMin = Math.min(allMin, encoded);
            allMax = Math.max(allMax, encoded);
            if (Double.doubleToRawLongBits(decodeF64(encoded, e, f)) == Double.doubleToRawLongBits(v)) {
                keptMin = Math.min(keptMin, encoded);
                keptMax = Math.max(keptMax, encoded);
            } else {
                patches++;
                if ((long) patches * PATCH_BYTES_F64 > limit) {
                    return -1;
                }
            }
        }
        boolean allPatched = patches == values.length;
        int bits = bitsForRange(allPatched ? allMin : keptMin, allPatched ? allMax : keptMax, Long.SIZE);
        return ((long) values.length * bits + 7) / 8 + (long) patches * PATCH_BYTES_F64;
    }

    /// Bits to bit-pack `[min, max]` after frame of reference: `ilog2(max - min) + 1`, `0` for a
    /// single value, and `typeBits` for an empty range or one whose difference overflows (Rust's
    /// `checked_sub` failing).
    private static int bitsForRange(long min, long max, int typeBits) {
        if (min > max) {
            return typeBits;
        }
        long diff = max - min;
        if (((max ^ min) & (max ^ diff)) < 0) {
            return typeBits;
        }
        return Long.SIZE - Long.numberOfLeadingZeros(diff);
    }

    private static AlpF64Data computeF64(double[] values) {
        int n = values.length;
        int[] exps = findExponentsF64(values);
        int expE = exps[0];
        int expF = exps[1];

        long[] encodedArr = new long[n];
        var patchIndices = new ArrayList<Integer>();
        var patchValues = new ArrayList<Double>();

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        long fill = 0L;
        boolean haveFill = false;
        for (int i = 0; i < n; i++) {
            double v = values[i];
            long encoded = encodeF64(v, expE, expF);
            encodedArr[i] = encoded;
            // Bit-exact, as Rust's is_eq: -0.0 encodes to 0 and decodes to +0.0, so it is an
            // exception like any other value that does not round-trip.
            if (Double.doubleToRawLongBits(decodeF64(encoded, expE, expF)) != Double.doubleToRawLongBits(v)) {
                patchIndices.add(i);
                patchValues.add(v);
            } else if (!haveFill) {
                fill = encoded;
                haveFill = true;
            }
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }
        // Rust's encode fills every exception's slot with the first value that did encode, so the
        // encoded child keeps its range (a 0 there widened prices' frame of reference); with no
        // such value the raw encodings stay, as in Rust.
        if (haveFill) {
            for (int idx : patchIndices) {
                encodedArr[idx] = fill;
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
            encodedBuf.setAtIndex(VortexFormat.LE_LONG, i, d.encodedArr()[i]);
        }

        EncodeNode encodedNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);

        if (d.patchIndices().isEmpty()) {
            byte[] metaBytes = new ProtoALPMetadata(d.expE(), d.expF(), null).encode();
            EncodeNode root = new EncodeNode(EncodingId.VORTEX_ALP,
                MemorySegment.ofArray(metaBytes), new EncodeNode[]{encodedNode}, new int[0]);
            return new EncodeResult(root, List.of(EncodedBuffer.of(encodedBuf, PType.I64)), d.statsMin(), d.statsMax());
        }

        int numPatches = d.patchIndices().size();
        MemorySegment idxBuf = ctx.arena().allocate((long) numPatches * 4, 4);
        MemorySegment valBuf = ctx.arena().allocate((long) numPatches * 8, 8);
        for (int i = 0; i < numPatches; i++) {
            idxBuf.setAtIndex(VortexFormat.LE_INT, i, d.patchIndices().get(i));
            valBuf.setAtIndex(VortexFormat.LE_DOUBLE, i, d.patchValues().get(i));
        }

        ProtoPatchesMetadata patches = buildPatchesMeta(numPatches);
        byte[] metaBytes = new ProtoALPMetadata(d.expE(), d.expF(), patches).encode();

        EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ALP,
            MemorySegment.ofArray(metaBytes),
            new EncodeNode[]{encodedNode, idxNode, valNode},
            new int[0]);
        return new EncodeResult(root, List.of(EncodedBuffer.of(encodedBuf, PType.I64), EncodedBuffer.of(idxBuf, PType.U32),
                EncodedBuffer.of(valBuf, PType.F64)), d.statsMin(), d.statsMax());
    }

    private static CascadeStep encodeCascadeF64(double[] values, EncodeContext ctx) {
        AlpF64Data d = computeF64(values);
        if (d.patchIndices().isEmpty()) {
            byte[] metaBytes = new ProtoALPMetadata(d.expE(), d.expF(), null).encode();
            EncodeNode partialRoot = new EncodeNode(EncodingId.VORTEX_ALP,
                MemorySegment.ofArray(metaBytes), new EncodeNode[1], new int[0]);
            ChildSlot slot = new ChildSlot(DType.I64, d.encodedArr(), 0, Set.of(EncodingId.VORTEX_ALP));
            return new CascadeStep(partialRoot, List.of(), List.of(slot), d.statsMin(), d.statsMax(), true);
        }

        int numPatches = d.patchIndices().size();
        MemorySegment idxBuf = ctx.arena().allocate((long) numPatches * 4, 4);
        MemorySegment valBuf = ctx.arena().allocate((long) numPatches * 8, 8);
        for (int i = 0; i < numPatches; i++) {
            idxBuf.setAtIndex(VortexFormat.LE_INT, i, d.patchIndices().get(i));
            valBuf.setAtIndex(VortexFormat.LE_DOUBLE, i, d.patchValues().get(i));
        }

        ProtoPatchesMetadata patches = buildPatchesMeta(numPatches);
        byte[] metaBytes = new ProtoALPMetadata(d.expE(), d.expF(), patches).encode();

        EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode partialRoot = new EncodeNode(EncodingId.VORTEX_ALP,
            MemorySegment.ofArray(metaBytes), new EncodeNode[]{null, idxNode, valNode}, new int[0]);
        ChildSlot slot = new ChildSlot(DType.I64, d.encodedArr(), 0, Set.of(EncodingId.VORTEX_ALP));
        return new CascadeStep(partialRoot, List.of(EncodedBuffer.of(idxBuf, PType.U32), EncodedBuffer.of(valBuf, PType.F64)),
                List.of(slot), d.statsMin(), d.statsMax(), true);
    }

    private static int encodeF32(float v, int e, int f) {
        return (int) ((v * F10_F32[e] * IF10_F32[f] + SWEET_F32) - SWEET_F32);
    }

    private static float decodeF32(int encoded, int e, int f) {
        return encoded * F10_F32[f] * IF10_F32[e];
    }

    /// See [#sampleF64(double[])].
    private static float[] sampleF32(float[] values) {
        int n = values.length;
        if (n <= SAMPLE_SIZE) {
            return values;
        }
        int blocks = SAMPLE_SIZE / SAMPLE_BLOCK;
        int spacing = (n - SAMPLE_BLOCK) / (blocks - 1);
        float[] sample = new float[blocks * SAMPLE_BLOCK];
        for (int i = 0; i < blocks; i++) {
            System.arraycopy(values, i * spacing, sample, i * SAMPLE_BLOCK, SAMPLE_BLOCK);
        }
        return sample;
    }

    /// See [#findExponentsF64(double[])].
    private static int[] findExponentsF32(float[] values) {
        float[] sample = sampleF32(values);
        int bestE = 0;
        int bestF = 0;
        long bestSize = estimateEncodedSizeF32(sample, 0, 0, Long.MAX_VALUE);
        for (int e = MAX_EXPONENT_F32 - 1; e >= 0; e--) {
            for (int f = 0; f <= e; f++) {
                long size = estimateEncodedSizeF32(sample, e, f, bestSize);
                if (size >= 0 && (size < bestSize || size == bestSize && e - f < bestE - bestF)) {
                    bestSize = size;
                    bestE = e;
                    bestF = f;
                }
            }
        }
        return new int[]{bestE, bestF};
    }

    /// See [#estimateEncodedSizeF64(double[], int, int, long)]; the range is over `i32`.
    private static long estimateEncodedSizeF32(float[] values, int e, int f, long limit) {
        int keptMin = Integer.MAX_VALUE;
        int keptMax = Integer.MIN_VALUE;
        int allMin = Integer.MAX_VALUE;
        int allMax = Integer.MIN_VALUE;
        int patches = 0;
        for (float v : values) {
            int encoded = encodeF32(v, e, f);
            allMin = Math.min(allMin, encoded);
            allMax = Math.max(allMax, encoded);
            if (Float.floatToRawIntBits(decodeF32(encoded, e, f)) == Float.floatToRawIntBits(v)) {
                keptMin = Math.min(keptMin, encoded);
                keptMax = Math.max(keptMax, encoded);
            } else {
                patches++;
                if ((long) patches * PATCH_BYTES_F32 > limit) {
                    return -1;
                }
            }
        }
        boolean allPatched = patches == values.length;
        int min = allPatched ? allMin : keptMin;
        int max = allPatched ? allMax : keptMax;
        // An i32 difference never overflows a long, so only the empty range needs the type width.
        int bits = min > max ? Integer.SIZE : Long.SIZE - Long.numberOfLeadingZeros((long) max - min);
        return ((long) values.length * bits + 7) / 8 + (long) patches * PATCH_BYTES_F32;
    }

    private static EncodeResult encodeF32(float[] values, EncodeContext ctx) {
        int n = values.length;
        int[] exps = findExponentsF32(values);
        int expE = exps[0];
        int expF = exps[1];
        int[] encodedArr = new int[n];
        var patchIndices = new ArrayList<Integer>();
        var patchValues = new ArrayList<Float>();

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        int fill = 0;
        boolean haveFill = false;
        for (int i = 0; i < n; i++) {
            float v = values[i];
            int encoded = encodeF32(v, expE, expF);
            encodedArr[i] = encoded;
            // Bit-exact: see the F64 path above for why (-0.0 vs +0.0).
            if (Float.floatToRawIntBits(decodeF32(encoded, expE, expF)) != Float.floatToRawIntBits(v)) {
                patchIndices.add(i);
                patchValues.add(v);
            } else if (!haveFill) {
                fill = encoded;
                haveFill = true;
            }
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }
        if (haveFill) {
            for (int idx : patchIndices) {
                encodedArr[idx] = fill;
            }
        }

        byte[] statsMin = n > 0 ? scalarF32(min) : null;
        byte[] statsMax = n > 0 ? scalarF32(max) : null;

        MemorySegment encodedBuf = ctx.arena().allocate((long) n * 4, 4);
        for (int i = 0; i < n; i++) {
            encodedBuf.setAtIndex(VortexFormat.LE_INT, i, encodedArr[i]);
        }

        EncodeNode encodedNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);

        if (patchIndices.isEmpty()) {
            byte[] metaBytes = new ProtoALPMetadata(expE, expF, null).encode();
            EncodeNode root = new EncodeNode(EncodingId.VORTEX_ALP,
                MemorySegment.ofArray(metaBytes), new EncodeNode[]{encodedNode}, new int[0]);
            return new EncodeResult(root, List.of(EncodedBuffer.of(encodedBuf, PType.I32)), statsMin, statsMax);
        }

        int numPatches = patchIndices.size();
        MemorySegment idxBuf = ctx.arena().allocate((long) numPatches * 4, 4);
        MemorySegment valBuf = ctx.arena().allocate((long) numPatches * 4, 4);
        for (int i = 0; i < numPatches; i++) {
            idxBuf.setAtIndex(VortexFormat.LE_INT, i, patchIndices.get(i));
            valBuf.setAtIndex(VortexFormat.LE_FLOAT, i, patchValues.get(i));
        }

        ProtoPatchesMetadata patches = new ProtoPatchesMetadata(
                numPatches,
                0L,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.U32.ordinal()),
                null, null, null);
        byte[] metaBytes = new ProtoALPMetadata(expE, expF, patches).encode();

        EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ALP,
            MemorySegment.ofArray(metaBytes),
            new EncodeNode[]{encodedNode, idxNode, valNode},
            new int[0]);
        return new EncodeResult(root, List.of(EncodedBuffer.of(encodedBuf, PType.I32), EncodedBuffer.of(idxBuf, PType.U32),
                EncodedBuffer.of(valBuf, PType.F32)), statsMin, statsMax);
    }

    private static ProtoPatchesMetadata buildPatchesMeta(int numPatches) {
        return new ProtoPatchesMetadata(
                numPatches,
                0L,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.U32.ordinal()),
                null, null, null);
    }

    private static byte[] scalarF64(double v) {
        return ProtoScalarValue.ofF64Value(v).encode();
    }

    private static byte[] scalarF32(float v) {
        return ProtoScalarValue.ofF32Value(v).encode();
    }

    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record AlpF64Data(int expE, int expF, long[] encodedArr,
                              List<Integer> patchIndices, List<Double> patchValues,
                              byte[] statsMin, byte[] statsMax) {
    }
}
