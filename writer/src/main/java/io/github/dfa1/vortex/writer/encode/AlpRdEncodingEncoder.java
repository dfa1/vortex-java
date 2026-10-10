package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoALPRDMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Write-only encoder for `vortex.alprd`.
public final class AlpRdEncodingEncoder implements EncodingEncoder {

    /// Rust's `alp` crate trains the dictionary on at most this many values (`MAX_SAMPLE`).
    private static final int MAX_SAMPLE = 4096;
    /// Length of each contiguous run of the training sample (`SAMPLE_BLOCK`).
    private static final int SAMPLE_BLOCK = 64;
    private static final int MAX_CUT = 16;
    private static final int MAX_DICT_SIZE = 8;

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ALPRD;
    }

    @Override
    public boolean accepts(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return false;
        }
        return p.ptype() == PType.F32 || p.ptype() == PType.F64;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        return switch (ptype) {
            case F64 -> encodeF64((double[]) data, ctx);
            case F32 -> encodeF32((float[]) data, ctx);
            default -> throw new UnsupportedOperationException("ALP-RD encode not supported for " + ptype);
        };
    }

    private static EncodeResult encodeF64(double[] values, EncodeContext ctx) {
        int n = values.length;
        if (n == 0) {
            return emptyResult(DType.U64, ctx);
        }

        // Rust's SamplePlan::subsample: contiguous runs spread over the whole array, so a periodic
        // input cannot hide one phase from the dictionary, and a head-only sample cannot flood
        // the tail with exceptions (#304 review).
        double[] sample = sampleF64(values);
        int sampleLen = sample.length;
        Dictionary64 best = findBestDictionaryF64(sample, sampleLen);

        Map<Short, Short> lookup = buildLookup(best.dict);
        long rightMask = -1L >>> (64 - best.rightBitWidth);

        short[] leftCodes = new short[n];
        long[] rightParts = new long[n];
        List<Long> excPos = new ArrayList<>();
        List<Short> excVals = new ArrayList<>();

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double v = values[i];
            long bits = Double.doubleToRawLongBits(v);
            short leftU16 = (short) (bits >>> best.rightBitWidth);
            rightParts[i] = bits & rightMask;
            Short code = lookup.get(leftU16);
            if (code != null) {
                leftCodes[i] = code;
            } else {
                leftCodes[i] = 0;
                excPos.add((long) i);
                excVals.add(leftU16);
            }
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }

        return buildEncodeResult(
            best.dict, best.rightBitWidth, leftCodes, rightParts,
            DType.U64, excPos, excVals, scalarF64(min), scalarF64(max), ctx);
    }

    /// Rust's `SamplePlan::subsample` (`alp` crate): the whole input when it has at most
    /// [#MAX_SAMPLE] values (no run starts: the caller uses it as is), else [#MAX_SAMPLE] values as evenly spread
    /// [#SAMPLE_BLOCK]-value runs.
    private static int[] sampleStarts(int n) {
        if (n <= MAX_SAMPLE) {
            return new int[0];
        }
        int blocks = MAX_SAMPLE / SAMPLE_BLOCK;
        int spacing = (n - SAMPLE_BLOCK) / (blocks - 1);
        int[] starts = new int[blocks];
        for (int i = 0; i < blocks; i++) {
            starts[i] = i * spacing;
        }
        return starts;
    }

    private static double[] sampleF64(double[] values) {
        int[] starts = sampleStarts(values.length);
        if (starts.length == 0) {
            return values;
        }
        double[] sample = new double[starts.length * SAMPLE_BLOCK];
        for (int i = 0; i < starts.length; i++) {
            System.arraycopy(values, starts[i], sample, i * SAMPLE_BLOCK, SAMPLE_BLOCK);
        }
        return sample;
    }

    private static float[] sampleF32(float[] values) {
        int[] starts = sampleStarts(values.length);
        if (starts.length == 0) {
            return values;
        }
        float[] sample = new float[starts.length * SAMPLE_BLOCK];
        for (int i = 0; i < starts.length; i++) {
            System.arraycopy(values, starts[i], sample, i * SAMPLE_BLOCK, SAMPLE_BLOCK);
        }
        return sample;
    }

    private static Dictionary64 findBestDictionaryF64(double[] values, int sampleLen) {
        double bestEstSize = Double.MAX_VALUE;
        int bestRightBw = 48;
        short[] bestDict = new short[]{0};

        for (int p = 1; p <= MAX_CUT; p++) {
            int rightBw = 64 - p;
            Map<Short, Integer> counts = new HashMap<>();
            for (int i = 0; i < sampleLen; i++) {
                long bits = Double.doubleToRawLongBits(values[i]);
                short leftU16 = (short) (bits >>> rightBw);
                counts.merge(leftU16, 1, Integer::sum);
            }
            short[] dict = topKByCount(counts);
            int excCount = countExceptionsF64(values, sampleLen, dict, rightBw);
            int maxCode = dict.length - 1;
            int leftBw = maxCode == 0 ? 1 : (Integer.SIZE - Integer.numberOfLeadingZeros(maxCode));
            double estSize = rightBw + leftBw + (double) (excCount * 32) / sampleLen;
            if (estSize < bestEstSize) {
                bestEstSize = estSize;
                bestRightBw = rightBw;
                bestDict = dict;
            }
        }
        return new Dictionary64(bestDict, bestRightBw);
    }

    private static int countExceptionsF64(double[] values, int sampleLen, short[] dict, int rightBw) {
        Map<Short, Boolean> dictSet = new HashMap<>();
        for (short d : dict) {
            dictSet.put(d, Boolean.TRUE);
        }
        int count = 0;
        for (int i = 0; i < sampleLen; i++) {
            long bits = Double.doubleToRawLongBits(values[i]);
            short leftU16 = (short) (bits >>> rightBw);
            if (!dictSet.containsKey(leftU16)) {
                count++;
            }
        }
        return count;
    }

    private static EncodeResult encodeF32(float[] values, EncodeContext ctx) {
        int n = values.length;
        if (n == 0) {
            return emptyResult(DType.U32, ctx);
        }

        // Same sample plan as encodeF64.
        float[] sample = sampleF32(values);
        int sampleLen = sample.length;
        Dictionary32 best = findBestDictionaryF32(sample, sampleLen);

        Map<Short, Short> lookup = buildLookup(best.dict);
        int rightMask = -1 >>> (32 - best.rightBitWidth);

        short[] leftCodes = new short[n];
        int[] rightParts = new int[n];
        List<Long> excPos = new ArrayList<>();
        List<Short> excVals = new ArrayList<>();

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float v = values[i];
            int bits = Float.floatToRawIntBits(v);
            short leftU16 = (short) (bits >>> best.rightBitWidth);
            rightParts[i] = bits & rightMask;
            Short code = lookup.get(leftU16);
            if (code != null) {
                leftCodes[i] = code;
            } else {
                leftCodes[i] = 0;
                excPos.add((long) i);
                excVals.add(leftU16);
            }
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }

        return buildEncodeResult(
            best.dict, best.rightBitWidth, leftCodes, rightParts,
            DType.U32, excPos, excVals, scalarF32(min), scalarF32(max), ctx);
    }

    private static Dictionary32 findBestDictionaryF32(float[] values, int sampleLen) {
        double bestEstSize = Double.MAX_VALUE;
        int bestRightBw = 16;
        short[] bestDict = new short[]{0};

        for (int p = 1; p <= MAX_CUT; p++) {
            int rightBw = 32 - p;
            Map<Short, Integer> counts = new HashMap<>();
            for (int i = 0; i < sampleLen; i++) {
                int bits = Float.floatToRawIntBits(values[i]);
                short leftU16 = (short) (bits >>> rightBw);
                counts.merge(leftU16, 1, Integer::sum);
            }
            short[] dict = topKByCount(counts);
            int excCount = countExceptionsF32(values, sampleLen, dict, rightBw);
            int maxCode = dict.length - 1;
            int leftBw = maxCode == 0 ? 1 : (Integer.SIZE - Integer.numberOfLeadingZeros(maxCode));
            double estSize = rightBw + leftBw + (double) (excCount * 32) / sampleLen;
            if (estSize < bestEstSize) {
                bestEstSize = estSize;
                bestRightBw = rightBw;
                bestDict = dict;
            }
        }
        return new Dictionary32(bestDict, bestRightBw);
    }

    private static int countExceptionsF32(float[] values, int sampleLen, short[] dict, int rightBw) {
        Map<Short, Boolean> dictSet = new HashMap<>();
        for (short d : dict) {
            dictSet.put(d, Boolean.TRUE);
        }
        int count = 0;
        for (int i = 0; i < sampleLen; i++) {
            int bits = Float.floatToRawIntBits(values[i]);
            short leftU16 = (short) (bits >>> rightBw);
            if (!dictSet.containsKey(leftU16)) {
                count++;
            }
        }
        return count;
    }

    private static short[] topKByCount(Map<Short, Integer> counts) {
        List<Map.Entry<Short, Integer>> sorted = new ArrayList<>(counts.entrySet());
        // Rust's select_dictionary: most frequent first, ties by ascending (unsigned) pattern, so the
        // dictionary is decided by the data and not by HashMap iteration order.
        sorted.sort(Map.Entry.<Short, Integer>comparingByValue().reversed()
                .thenComparing(e -> Short.toUnsignedInt(e.getKey())));
        int dictSize = Math.min(sorted.size(), MAX_DICT_SIZE);
        short[] dict = new short[dictSize];
        for (int i = 0; i < dictSize; i++) {
            dict[i] = sorted.get(i).getKey();
        }
        return dict;
    }

    private static Map<Short, Short> buildLookup(short[] dict) {
        Map<Short, Short> lookup = new HashMap<>();
        for (short i = 0; i < dict.length; i++) {
            lookup.put(dict[i], i);
        }
        return lookup;
    }

    private static EncodeResult buildEncodeResult(
        short[] dict, int rightBitWidth,
        short[] leftCodes, Object rightPartsData, DType rightDtype,
        List<Long> excPos, List<Short> excVals, byte[] statsMin, byte[] statsMax, EncodeContext ctx) {

        EncodingEncoder bp = ctx.lookupEncoder(EncodingId.FASTLANES_BITPACKED);
        EncodeResult leftResult = bp.encode(DType.U16, leftCodes, ctx);
        EncodeResult rightResult = bp.encode(rightDtype, rightPartsData, ctx);

        List<EncodedBuffer> allBuffers = new ArrayList<>(leftResult.encodedBuffers());
        int leftBufCount = allBuffers.size();
        allBuffers.addAll(rightResult.encodedBuffers());

        EncodeNode leftNode = EncodeNode.remapBufferIndices(leftResult.rootNode(), 0);
        EncodeNode rightNode = EncodeNode.remapBufferIndices(rightResult.rootNode(), leftBufCount);

        List<Integer> dictList = new ArrayList<>(dict.length);
        for (short d : dict) {
            dictList.add(d & 0xFFFF);
        }

        EncodeNode[] children;
        ProtoPatchesMetadata patchesMeta = null;
        if (excPos.isEmpty()) {
            children = new EncodeNode[]{leftNode, rightNode};
        } else {
            long[] excPosArr = excPos.stream().mapToLong(Long::longValue).toArray();
            short[] excValsArr = new short[excVals.size()];
            for (int i = 0; i < excVals.size(); i++) {
                excValsArr[i] = excVals.get(i);
            }

            // Rust's compress_patches: indices narrowed to the smallest unsigned type that holds them,
            // values left as they are (a constant array when they are all equal), and none of it
            // bit-packed. Bit-packing pads to 1024-value blocks, which inflates the handful of
            // exceptions of a small sample and biases the cascade away from ALP-RD.
            PType idxType = PatchBuffers.narrowestUnsigned(excPosArr[excPosArr.length - 1]);
            int idxOffset = allBuffers.size();
            allBuffers.add(EncodedBuffer.of(PatchBuffers.unsigned(excPosArr, idxType, ctx), idxType));
            EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, idxOffset);

            EncodeNode valNode;
            int valOffset = allBuffers.size();
            if (SimdOperationsSupport.preferred().allEqual(excValsArr, PType.I16)) {
                EncodeResult constant = new ConstantEncodingEncoder().encode(DType.U16, excValsArr, ctx);
                allBuffers.addAll(constant.encodedBuffers());
                valNode = EncodeNode.remapBufferIndices(constant.rootNode(), valOffset);
            } else {
                MemorySegment valBuf = ctx.arena().allocate((long) excValsArr.length * Short.BYTES, Short.BYTES);
                MemorySegment.copy(excValsArr, 0, valBuf, VortexFormat.LE_SHORT, 0, excValsArr.length);
                allBuffers.add(EncodedBuffer.of(valBuf, PType.U16));
                valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, valOffset);
            }

            patchesMeta = new ProtoPatchesMetadata(
                    excPos.size(),
                    0L,
                    io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(idxType.ordinal()),
                    null, null, null);
            children = new EncodeNode[]{leftNode, rightNode, idxNode, valNode};
        }

        byte[] metaBytes = new ProtoALPRDMetadata(
                rightBitWidth,
                dict.length,
                dictList,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.U16.ordinal()),
                patchesMeta
        ).encode();
        EncodeNode root = new EncodeNode(
            EncodingId.VORTEX_ALPRD, MemorySegment.ofArray(metaBytes), children, new int[]{});
        return new EncodeResult(root, List.copyOf(allBuffers), statsMin, statsMax);
    }

    private static byte[] scalarF64(double v) {
        return ProtoScalarValue.ofF64Value(v).encode();
    }

    private static byte[] scalarF32(float v) {
        return ProtoScalarValue.ofF32Value(v).encode();
    }

    private static EncodeResult emptyResult(DType rightDtype, EncodeContext ctx) {
        EncodingEncoder bp = ctx.lookupEncoder(EncodingId.FASTLANES_BITPACKED);
        EncodeResult leftResult = bp.encode(DType.U16, new short[0], ctx);
        EncodeResult rightResult = bp.encode(rightDtype,
            rightDtype.equals(DType.U32) ? new int[0] : new long[0], ctx);

        List<EncodedBuffer> allBuffers = new ArrayList<>(leftResult.encodedBuffers());
        int leftBufCount = allBuffers.size();
        allBuffers.addAll(rightResult.encodedBuffers());

        EncodeNode leftNode = EncodeNode.remapBufferIndices(leftResult.rootNode(), 0);
        EncodeNode rightNode = EncodeNode.remapBufferIndices(rightResult.rootNode(), leftBufCount);

        byte[] metaBytes = new ProtoALPRDMetadata(
                48,
                0,
                List.of(),
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.U16.ordinal()),
                null).encode();

        EncodeNode root = new EncodeNode(
            EncodingId.VORTEX_ALPRD, MemorySegment.ofArray(metaBytes),
            new EncodeNode[]{leftNode, rightNode}, new int[]{});
        return new EncodeResult(root, List.copyOf(allBuffers), null, null);
    }

    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record Dictionary64(short[] dict, int rightBitWidth) {
    }

    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record Dictionary32(short[] dict, int rightBitWidth) {
    }
}
