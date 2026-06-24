package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoPatchedMetadata;

import java.lang.foreign.MemorySegment;
import java.util.List;

/// Write-only encoder for `vortex.patched`.
///
/// Identifies a small fraction of outlier values (those requiring more bits than the
/// optimal target width), replaces them with 0 in the inner array, and stores their
/// within-chunk indices (U16) and actual values separately. The inner array is exposed
/// as an open cascade child so the compressor can further bitpack it at the reduced
/// bit width.
///
/// Skips when no bit-width improvement is possible, or when more than 5% of values
/// are outliers (too many patches to be worthwhile).
public final class PatchedEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public PatchedEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PATCHED;
    }

    @Override
    public boolean accepts(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return false;
        }
        return switch (p.ptype()) {
            case I8, U8, I16, U16, I32, U32, I64, U64 -> true;
            default -> false;
        };
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode(dtype, data, ctx);
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encodeCascade(dtype, data);
    }

    private static final class Encoder {
        private static final int CHUNK_SIZE = 1024;
        private static final int N_LANES = 1;

        static CascadeStep encodeCascade(DType dtype, Object data) {
            if (!(dtype instanceof DType.Primitive p)) {
                return CascadeStep.notApplicable();
            }
            PType ptype = p.ptype();
            long[] longs = PrimitiveArrays.toLongs(data, ptype, EncodingId.VORTEX_PATCHED);
            int n = longs.length;
            if (n == 0) {
                return CascadeStep.notApplicable();
            }

            PatchedData pd = computePatchedData(longs, ptype, n);
            if (pd == null) {
                return CascadeStep.notApplicable();
            }

            byte[] metaBytes = new ProtoPatchedMetadata(pd.nPatches, N_LANES, 0).encode();
            EncodeNode partialRoot = new EncodeNode(EncodingId.VORTEX_PATCHED,
                    MemorySegment.ofArray(metaBytes), new EncodeNode[]{null, null, null, null}, new int[]{});

            DType u32Dtype = DType.U32;
            DType u16Dtype = DType.U16;

            return new CascadeStep(partialRoot, List.of(),
                    List.of(
                            new ChildSlot(dtype, fromLongs(pd.inner, ptype), 0),
                            new ChildSlot(u32Dtype, pd.laneOffsets, 1),
                            new ChildSlot(u16Dtype, pd.patchIndices, 2),
                            new ChildSlot(dtype, fromLongs(pd.patchValues, ptype), 3)
                    ),
                    null, null, true);
        }

        static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
            if (!(dtype instanceof DType.Primitive p)) {
                throw new VortexException(EncodingId.VORTEX_PATCHED,
                        "expected primitive dtype, got " + dtype);
            }
            PType ptype = p.ptype();
            long[] longs = PrimitiveArrays.toLongs(data, ptype, EncodingId.VORTEX_PATCHED);
            int n = longs.length;

            PatchedData pd = computePatchedData(longs, ptype, n);
            if (pd == null) {
                pd = noPatches(longs, n);
            }

            int elemBytes = ptype.byteSize();

            MemorySegment innerBuf = ctx.arena().allocate((long) n * elemBytes);
            for (int i = 0; i < n; i++) {
                PTypeIO.set(innerBuf, (long) i * elemBytes, ptype, pd.inner[i]);
            }

            MemorySegment laneOffsBuf = ctx.arena().allocate(pd.laneOffsets.length * 4L);
            for (int i = 0; i < pd.laneOffsets.length; i++) {
                laneOffsBuf.setAtIndex(PTypeIO.LE_INT, i, pd.laneOffsets[i]);
            }

            MemorySegment patchIdxBuf = ctx.arena().allocate(Math.max(1L, (long) pd.nPatches * 2));
            for (int i = 0; i < pd.nPatches; i++) {
                patchIdxBuf.setAtIndex(PTypeIO.LE_SHORT, i, pd.patchIndices[i]);
            }

            MemorySegment patchValBuf = ctx.arena().allocate(Math.max(1L, (long) pd.nPatches * elemBytes));
            for (int i = 0; i < pd.nPatches; i++) {
                PTypeIO.set(patchValBuf, (long) i * elemBytes, ptype, pd.patchValues[i]);
            }

            byte[] metaBytes = new ProtoPatchedMetadata(pd.nPatches, N_LANES, 0).encode();

            EncodeNode innerNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
            EncodeNode laneNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
            EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
            EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 3);
            EncodeNode root = new EncodeNode(EncodingId.VORTEX_PATCHED, MemorySegment.ofArray(metaBytes),
                    new EncodeNode[]{innerNode, laneNode, idxNode, valNode}, new int[]{});

            return new EncodeResult(root, List.of(innerBuf, laneOffsBuf, patchIdxBuf, patchValBuf), null, null);
        }

        private static PatchedData computePatchedData(long[] longs, PType ptype, int n) {
            int typeBits = ptype.bits();
            long typeMask = FastLanes.lowMask(typeBits);
            int elemBytes = ptype.byteSize();

            int[] bitWidthFreq = new int[typeBits + 1];
            for (long v : longs) {
                long uv = v & typeMask;
                int width = uv == 0L ? 0 : Long.SIZE - Long.numberOfLeadingZeros(uv);
                bitWidthFreq[width]++;
            }

            // bytes per patch: U16 within-chunk index + value
            int bytesPerPatch = 2 + elemBytes;
            int bestWidth = bestBitWidth(bitWidthFreq, bytesPerPatch, n, elemBytes);

            if (bestWidth >= typeBits) {
                return null;
            }

            long widthCap = 1L << bestWidth;

            // First pass: count outliers per chunk
            int nChunks = (n + CHUNK_SIZE - 1) / CHUNK_SIZE;
            int[] patchCountPerChunk = new int[nChunks];
            int totalPatches = 0;
            int posInChunk = 0;
            int chunkIdx = 0;
            for (int i = 0; i < n; i++) {
                if (posInChunk == CHUNK_SIZE) {
                    posInChunk = 0;
                    chunkIdx++;
                }
                long uv = longs[i] & typeMask;
                if (Long.compareUnsigned(uv, widthCap) >= 0) {
                    patchCountPerChunk[chunkIdx]++;
                    totalPatches++;
                }
                posInChunk++;
            }

            if (totalPatches == 0 || totalPatches > n / 20) {
                return null;
            }

            return buildPatches(longs, typeMask, widthCap, n, nChunks, patchCountPerChunk, totalPatches);
        }

        private static PatchedData buildPatches(long[] longs, long typeMask, long widthCap, int n,
                int nChunks, int[] patchCountPerChunk, int totalPatches) {
            // Build laneOffsets CSR: N_LANES=1 → nChunks+1 entries
            int[] laneOffsets = new int[nChunks + 1];
            for (int c = 0; c < nChunks; c++) {
                laneOffsets[c + 1] = laneOffsets[c] + patchCountPerChunk[c];
            }

            long[] inner = longs.clone();
            short[] patchIndices = new short[totalPatches];
            long[] patchValues = new long[totalPatches];

            // Second pass: fill patches in chunk order (natural iteration order)
            int pIdx = 0;
            int posInChunk = 0;
            for (int i = 0; i < n; i++) {
                if (posInChunk == CHUNK_SIZE) {
                    posInChunk = 0;
                }
                long uv = longs[i] & typeMask;
                if (Long.compareUnsigned(uv, widthCap) >= 0) {
                    patchIndices[pIdx] = (short) posInChunk;
                    patchValues[pIdx] = longs[i];
                    inner[i] = 0L;
                    pIdx++;
                }
                posInChunk++;
            }

            return new PatchedData(totalPatches, inner, laneOffsets, patchIndices, patchValues);
        }

        private static PatchedData noPatches(long[] longs, int n) {
            int nChunks = Math.max(1, (n + CHUNK_SIZE - 1) / CHUNK_SIZE);
            return new PatchedData(0, longs, new int[nChunks + 1], new short[0], new long[0]);
        }

        private static int bestBitWidth(int[] bitWidthFreq, int bytesPerPatch, int n, int elemBytes) {
            long bestCost = (long) n * elemBytes;
            int typeBits = bitWidthFreq.length - 1;
            int bestWidth = typeBits;
            long numFit = 0;
            for (int width = 0; width <= typeBits; width++) {
                numFit += bitWidthFreq[width];
                long packedCost = ((long) width * n + 7L) / 8L;
                long exceptionsCost = (n - numFit) * bytesPerPatch;
                long cost = packedCost + exceptionsCost;
                if (cost < bestCost) {
                    bestCost = cost;
                    bestWidth = width;
                }
            }
            return bestWidth;
        }

        private static Object fromLongs(long[] values, PType ptype) {
            return switch (ptype) {
                case I8, U8 -> {
                    byte[] r = new byte[values.length];
                    for (int i = 0; i < values.length; i++) {
                        r[i] = (byte) values[i];
                    }
                    yield r;
                }
                case I16, U16 -> {
                    short[] r = new short[values.length];
                    for (int i = 0; i < values.length; i++) {
                        r[i] = (short) values[i];
                    }
                    yield r;
                }
                case I32, U32 -> {
                    int[] r = new int[values.length];
                    for (int i = 0; i < values.length; i++) {
                        r[i] = (int) values[i];
                    }
                    yield r;
                }
                case I64, U64 -> values.clone();
                default -> throw new VortexException(EncodingId.VORTEX_PATCHED, "unsupported ptype: " + ptype);
            };
        }

    }

    @SuppressWarnings("java:S6218") // internal data carrier; array fields are not compared for equality
    private record PatchedData(
            int nPatches,
            long[] inner,
            int[] laneOffsets,
            short[] patchIndices,
            long[] patchValues
    ) {
    }
}
