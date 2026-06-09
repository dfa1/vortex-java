package io.github.dfa1.vortex.encoding;

import java.io.IOException;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.proto.ALPRDMetadata;
import io.github.dfa1.vortex.proto.PatchesMetadata;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Encoder/decoder for {@code vortex.alprd} — ALP Real Doubles.
///
/// <p>Splits each float's bit pattern at a cut point {@code p} (1..16 MSBs):
/// left {@code p} bits are dictionary-coded (≤8 entries, codes bitpacked to 1–3 bits);
/// right {@code BITS-p} bits are bitpacked directly.
/// Values whose left bits fall outside the dictionary are stored as exceptions (patches).
///
/// <p>Metadata: protobuf {@code ALPRDMetadata} — {@code right_bit_width u32} (tag 1),
/// {@code dict_len u32} (tag 2), {@code dict repeated u32} (tag 3),
/// {@code left_parts_ptype PType} (tag 4), optional {@code patches PatchesMetadata} (tag 5).
///
/// <p>Children:
/// <ul>
///   <li>0: left_parts — bitpacked U16 dictionary codes</li>
///   <li>1: right_parts — bitpacked U32 (F32) or U64 (F64) right bit-patterns</li>
///   <li>2: patch_indices (optional) — bitpacked U64 exception positions</li>
///   <li>3: patch_values (optional) — U16 raw left bit-patterns for exceptions</li>
/// </ul>
public final class AlpRdEncoding implements Encoding {

    private static final DType U16_DTYPE = new DType.Primitive(PType.U16, false);
    private static final DType U32_DTYPE = new DType.Primitive(PType.U32, false);
    private static final DType U64_DTYPE = new DType.Primitive(PType.U64, false);

    /// Creates a new {@code AlpRdEncoding} instance; use via {@link Registry}.
    public AlpRdEncoding() {
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
        return Encoder.encode(dtype, data, ctx);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    // -------------------------------------------------------------------------
    // Encoder
    // -------------------------------------------------------------------------

    private static final class Encoder {

        private static final int SAMPLE_SIZE = 512;
        private static final int MAX_CUT = 16;
        private static final int MAX_DICT_SIZE = 8;

        static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
            PType ptype = ((DType.Primitive) dtype).ptype();
            return switch (ptype) {
                case F64 -> encodeF64((double[]) data, ctx);
                case F32 -> encodeF32((float[]) data, ctx);
                default -> throw new UnsupportedOperationException("ALP-RD encode not supported for " + ptype);
            };
        }

        // --- F64 ---

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
                int excCount = countExceptions(values, sampleLen, dict, rightBw);
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

        private static int countExceptions(double[] values, int sampleLen, short[] dict, int rightBw) {
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

        // --- F32 ---

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
            int dictSize = Math.min(sorted.size(), Encoder.MAX_DICT_SIZE);
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

        // --- shared helpers ---

        private static EncodeResult buildEncodeResult(
            short[] dict, int rightBitWidth,
            short[] leftCodes, Object rightPartsData, DType rightDtype,
            List<Long> excPos, List<Short> excVals, EncodeContext ctx) {

            Encoding bp = ctx.lookupEncoding(EncodingId.FASTLANES_BITPACKED);
            EncodeResult leftResult = bp.encode(U16_DTYPE, leftCodes, ctx);
            EncodeResult rightResult = bp.encode(rightDtype, rightPartsData, ctx);

            List<MemorySegment> allBuffers = new ArrayList<>(leftResult.buffers());
            int leftBufCount = allBuffers.size();
            allBuffers.addAll(rightResult.buffers());

            EncodeNode leftNode = EncodeNode.remapBufferIndices(leftResult.rootNode(), 0);
            EncodeNode rightNode = EncodeNode.remapBufferIndices(rightResult.rootNode(), leftBufCount);

            java.util.List<Integer> dictList = new ArrayList<>(dict.length);
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
            Encoding bp = ctx.lookupEncoding(EncodingId.FASTLANES_BITPACKED);
            EncodeResult leftResult = bp.encode(AlpRdEncoding.U16_DTYPE, new short[0], ctx);
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
                    java.util.List.of(),
                    io.github.dfa1.vortex.proto.PType.fromValue(PType.U16.ordinal()),
                    null).encode();

            EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_ALPRD, ByteBuffer.wrap(metaBytes),
                new EncodeNode[]{leftNode, rightNode}, new int[]{});
            return new EncodeResult(root, List.copyOf(allBuffers), null, null);
        }

        private record Dictionary64(short[] dict, int rightBitWidth) {
        }

        private record Dictionary32(short[] dict, int rightBitWidth) {
        }
    }

    // -------------------------------------------------------------------------
    // Decoder
    // -------------------------------------------------------------------------

    private static final class Decoder {

        static Array decode(DecodeContext ctx) {
            ALPRDMetadata meta = parseMeta(ctx);

            if (!(ctx.dtype() instanceof DType.Primitive p)) {
                throw new VortexException(EncodingId.VORTEX_ALPRD,
                    "expected primitive dtype, got " + ctx.dtype());
            }

            int rightBitWidth = meta.right_bit_width();
            int dictLen = meta.dict_len();
            short[] dict = new short[dictLen];
            for (int i = 0; i < dictLen; i++) {
                dict[i] = (short) (meta.dict().get(i) & 0xFFFF);
            }

            long n = ctx.rowCount();
            PType ptype = p.ptype();

            return switch (ptype) {
                case F64 -> decodeF64(ctx, meta, dict, rightBitWidth, n);
                case F32 -> decodeF32(ctx, meta, dict, rightBitWidth, n);
                default -> throw new VortexException(EncodingId.VORTEX_ALPRD,
                    "unsupported dtype " + ptype);
            };
        }

        private static Array decodeF64(DecodeContext ctx,
            ALPRDMetadata meta,
            short[] dict, int rightBitWidth, long n) {
            MemorySegment leftSeg = ctx.decodeChildSegment(0, U16_DTYPE, n);
            MemorySegment rightSeg = ctx.decodeChildSegment(1, U64_DTYPE, n);
            long leftCap = SegmentBroadcast.capacity(leftSeg, 2);
            long rightCap = SegmentBroadcast.capacity(rightSeg, 8);
            MemorySegment out = ctx.arena().allocate(n * Long.BYTES, Long.BYTES);

            for (long i = 0; i < n; i++) {
                int code = Short.toUnsignedInt(leftSeg.getAtIndex(PTypeIO.LE_SHORT, i % leftCap));
                long leftBits = (long) (dict[code] & 0xFFFF) << rightBitWidth;
                long rightBits = rightSeg.getAtIndex(PTypeIO.LE_LONG, i % rightCap);
                out.setAtIndex(PTypeIO.LE_LONG, i, leftBits | rightBits);
            }

            if (meta.patches() != null) {
                applyPatchesF64(ctx, meta.patches(), out, rightSeg, rightCap, rightBitWidth);
            }

            return new DoubleArray(ctx.dtype(), n, out.asReadOnly());
        }

        private static Array decodeF32(DecodeContext ctx,
            ALPRDMetadata meta,
            short[] dict, int rightBitWidth, long n) {
            MemorySegment leftSeg = ctx.decodeChildSegment(0, U16_DTYPE, n);
            MemorySegment rightSeg = ctx.decodeChildSegment(1, U32_DTYPE, n);
            long leftCap = SegmentBroadcast.capacity(leftSeg, 2);
            long rightCap = SegmentBroadcast.capacity(rightSeg, 4);
            MemorySegment out = ctx.arena().allocate(n * Integer.BYTES, Integer.BYTES);

            for (long i = 0; i < n; i++) {
                int code = Short.toUnsignedInt(leftSeg.getAtIndex(PTypeIO.LE_SHORT, i % leftCap));
                int leftBits = (dict[code] & 0xFFFF) << rightBitWidth;
                int rightBits = rightSeg.getAtIndex(PTypeIO.LE_INT, i % rightCap);
                out.setAtIndex(PTypeIO.LE_INT, i, leftBits | rightBits);
            }

            if (meta.patches() != null) {
                applyPatchesF32(ctx, meta.patches(), out, rightSeg, rightCap, rightBitWidth);
            }

            return new FloatArray(ctx.dtype(), n, out.asReadOnly());
        }

        private static void applyPatchesF64(DecodeContext ctx,
            PatchesMetadata pm,
            MemorySegment out, MemorySegment rightSeg, long rightCap, int rightBitWidth) {
            long numPatches = pm.len();
            long offset = pm.offset();
            PType idxPtype = PType.fromOrdinal(pm.indices_ptype().value());

            MemorySegment idxSeg = ctx.decodeChildSegment(2, new DType.Primitive(idxPtype, false), numPatches);
            MemorySegment valSeg = ctx.decodeChildSegment(3, U16_DTYPE, numPatches);
            int idxBytes = idxPtype.byteSize();
            long valCap = SegmentBroadcast.capacity(valSeg, 2);

            for (long j = 0; j < numPatches; j++) {
                long absIdx = readUnsigned(idxSeg, SegmentBroadcast.elementOffset(idxSeg, j, idxBytes), idxPtype) - offset;
                short actualLeftU16 = valSeg.getAtIndex(PTypeIO.LE_SHORT, j % valCap);
                long leftBits = (long) (actualLeftU16 & 0xFFFF) << rightBitWidth;
                long rightBits = rightSeg.getAtIndex(PTypeIO.LE_LONG, absIdx % rightCap);
                out.setAtIndex(PTypeIO.LE_LONG, absIdx, leftBits | rightBits);
            }
        }

        private static void applyPatchesF32(DecodeContext ctx, PatchesMetadata pm,
            MemorySegment out, MemorySegment rightSeg, long rightCap, int rightBitWidth) {
            long numPatches = pm.len();
            long offset = pm.offset();
            PType idxPtype = PType.fromOrdinal(pm.indices_ptype().value());

            MemorySegment idxSeg = ctx.decodeChildSegment(2, new DType.Primitive(idxPtype, false), numPatches);
            MemorySegment valSeg = ctx.decodeChildSegment(3, U16_DTYPE, numPatches);
            int idxBytes = idxPtype.byteSize();
            long valCap = SegmentBroadcast.capacity(valSeg, 2);

            for (long j = 0; j < numPatches; j++) {
                long absIdx = readUnsigned(idxSeg, SegmentBroadcast.elementOffset(idxSeg, j, idxBytes), idxPtype) - offset;
                short actualLeftU16 = valSeg.getAtIndex(PTypeIO.LE_SHORT, j % valCap);
                int leftBits = (actualLeftU16 & 0xFFFF) << rightBitWidth;
                int rightBits = rightSeg.getAtIndex(PTypeIO.LE_INT, absIdx % rightCap);
                out.setAtIndex(PTypeIO.LE_INT, (int) absIdx, leftBits | rightBits);
            }
        }

        private static long readUnsigned(MemorySegment seg, long off, PType ptype) {
            return switch (ptype) {
                case U8 -> Byte.toUnsignedLong(seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, off));
                case U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, off));
                case U32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, off));
                case U64 -> seg.get(PTypeIO.LE_LONG, off);
                default -> throw new VortexException(EncodingId.VORTEX_ALPRD,
                    "non-unsigned patch index ptype " + ptype);
            };
        }

        private static ALPRDMetadata parseMeta(DecodeContext ctx) {
            ByteBuffer rawMeta = ctx.metadata();
            if (rawMeta == null || !rawMeta.hasRemaining()) {
                return new ALPRDMetadata(0, 0, java.util.List.of(),
                        io.github.dfa1.vortex.proto.PType.fromValue(PType.U16.ordinal()), null);
            }
            try {
                MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
                return ALPRDMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            } catch (IOException e) {
                throw new VortexException(EncodingId.VORTEX_ALPRD, "invalid metadata", e);
            }
        }
    }
}
