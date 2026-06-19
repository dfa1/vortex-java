package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.ALPRDMetadata;
import io.github.dfa1.vortex.proto.PatchesMetadata;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Write-only encoder for `vortex.alprd`.
public final class AlpRdEncodingEncoder implements EncodingEncoder {
    private static final DType U16_DTYPE = new DType.Primitive(PType.U16, false);
    private static final DType U32_DTYPE = new DType.Primitive(PType.U32, false);
    private static final DType U64_DTYPE = new DType.Primitive(PType.U64, false);

    private static final int SAMPLE_SIZE = 512;
    private static final int MAX_CUT = 16;
    private static final int MAX_DICT_SIZE = 8;

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public AlpRdEncodingEncoder() {
    }

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
            return emptyResult(U64_DTYPE, ctx);
        }

        int sampleLen = Math.min(SAMPLE_SIZE, n);
        Dictionary64 best = findBestDictionaryF64(values, sampleLen);

        Map<Short, Short> lookup = buildLookup(best.dict);
        long rightMask = -1L >>> (64 - best.rightBitWidth);

        short[] leftCodes = new short[n];
        long[] rightParts = new long[n];
        List<Long> excPos = new ArrayList<>();
        List<Short> excVals = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            long bits = Double.doubleToRawLongBits(values[i]);
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
        }

        return buildEncodeResult(
            best.dict, best.rightBitWidth, leftCodes, rightParts,
            U64_DTYPE, excPos, excVals, ctx);
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
            return emptyResult(U32_DTYPE, ctx);
        }

        int sampleLen = Math.min(SAMPLE_SIZE, n);
        Dictionary32 best = findBestDictionaryF32(values, sampleLen);

        Map<Short, Short> lookup = buildLookup(best.dict);
        int rightMask = -1 >>> (32 - best.rightBitWidth);

        short[] leftCodes = new short[n];
        int[] rightParts = new int[n];
        List<Long> excPos = new ArrayList<>();
        List<Short> excVals = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            int bits = Float.floatToRawIntBits(values[i]);
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
        }

        return buildEncodeResult(
            best.dict, best.rightBitWidth, leftCodes, rightParts,
            U32_DTYPE, excPos, excVals, ctx);
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
        sorted.sort((a, b) -> b.getValue() - a.getValue());
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
        List<Long> excPos, List<Short> excVals, EncodeContext ctx) {

        EncodingEncoder bp = ctx.lookupEncoder(EncodingId.FASTLANES_BITPACKED);
        EncodeResult leftResult = bp.encode(U16_DTYPE, leftCodes, ctx);
        EncodeResult rightResult = bp.encode(rightDtype, rightPartsData, ctx);

        List<MemorySegment> allBuffers = new ArrayList<>(leftResult.buffers());
        int leftBufCount = allBuffers.size();
        allBuffers.addAll(rightResult.buffers());

        EncodeNode leftNode = EncodeNode.remapBufferIndices(leftResult.rootNode(), 0);
        EncodeNode rightNode = EncodeNode.remapBufferIndices(rightResult.rootNode(), leftBufCount);

        List<Integer> dictList = new ArrayList<>(dict.length);
        for (short d : dict) {
            dictList.add(d & 0xFFFF);
        }

        EncodeNode[] children;
        PatchesMetadata patchesMeta = null;
        if (excPos.isEmpty()) {
            children = new EncodeNode[]{leftNode, rightNode};
        } else {
            long[] excPosArr = excPos.stream().mapToLong(Long::longValue).toArray();
            short[] excValsArr = new short[excVals.size()];
            for (int i = 0; i < excVals.size(); i++) {
                excValsArr[i] = excVals.get(i);
            }

            EncodeResult idxResult = bp.encode(U64_DTYPE, excPosArr, ctx);
            EncodeResult valResult = bp.encode(U16_DTYPE, excValsArr, ctx);

            int idxOffset = allBuffers.size();
            allBuffers.addAll(idxResult.buffers());
            int idxBufCount = idxResult.buffers().size();
            allBuffers.addAll(valResult.buffers());

            EncodeNode idxNode = EncodeNode.remapBufferIndices(idxResult.rootNode(), idxOffset);
            EncodeNode valNode = EncodeNode.remapBufferIndices(valResult.rootNode(), idxOffset + idxBufCount);

            patchesMeta = new PatchesMetadata(
                    (long) excPos.size(),
                    0L,
                    io.github.dfa1.vortex.proto.PType.fromValue(PType.U64.ordinal()),
                    null, null, null);
            children = new EncodeNode[]{leftNode, rightNode, idxNode, valNode};
        }

        byte[] metaBytes = new ALPRDMetadata(
                rightBitWidth,
                dict.length,
                dictList,
                io.github.dfa1.vortex.proto.PType.fromValue(PType.U16.ordinal()),
                patchesMeta
        ).encode();
        EncodeNode root = new EncodeNode(
            EncodingId.VORTEX_ALPRD, ByteBuffer.wrap(metaBytes), children, new int[]{});
        return new EncodeResult(root, List.copyOf(allBuffers), null, null);
    }

    private static EncodeResult emptyResult(DType rightDtype, EncodeContext ctx) {
        EncodingEncoder bp = ctx.lookupEncoder(EncodingId.FASTLANES_BITPACKED);
        EncodeResult leftResult = bp.encode(U16_DTYPE, new short[0], ctx);
        EncodeResult rightResult = bp.encode(rightDtype,
            rightDtype.equals(U32_DTYPE) ? new int[0] : new long[0], ctx);

        List<MemorySegment> allBuffers = new ArrayList<>(leftResult.buffers());
        int leftBufCount = allBuffers.size();
        allBuffers.addAll(rightResult.buffers());

        EncodeNode leftNode = EncodeNode.remapBufferIndices(leftResult.rootNode(), 0);
        EncodeNode rightNode = EncodeNode.remapBufferIndices(rightResult.rootNode(), leftBufCount);

        byte[] metaBytes = new ALPRDMetadata(
                48,
                0,
                List.of(),
                io.github.dfa1.vortex.proto.PType.fromValue(PType.U16.ordinal()),
                null).encode();

        EncodeNode root = new EncodeNode(
            EncodingId.VORTEX_ALPRD, ByteBuffer.wrap(metaBytes),
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
