package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.PcoChunkInfo;
import io.github.dfa1.vortex.proto.PcoMetadata;
import io.github.dfa1.vortex.proto.PcoPageInfo;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Write-only encoder for {@code vortex.pco}.
///
/// <p>Classic mode, 1 latent variable per chunk, single page.
/// Chooses between NoOp delta (deltaVariant=0) and Consecutive delta (deltaVariant=1, order=1)
/// by comparing estimated bit cost from the bin-optimization DP.
/// Uses a histogram + DP to find optimal ANS bins, then encodes values with 4-way interleaved
/// tANS + per-bin offset bits.
public final class PcoEncodingEncoder implements EncodingEncoder {

    private static final byte PCO_FORMAT_MAJOR = 0x04;
    private static final byte PCO_FORMAT_MINOR = 0x01;
    private static final int BATCH_N = 256;
    private static final int ANS_INTERLEAVING = 4;
    private static final int N_BINS_LOG = 8; // up to 256 histogram buckets (compression level 8)

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public PcoEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PCO;
    }

    @Override
    public boolean accepts(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return false;
        }
        return switch (p.ptype()) {
            case I16, U16, I32, U32, F32, I64, U64, F64 -> true;
            default -> false;
        };
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode(dtype, data, ctx);
    }

    private static final class Encoder {

        static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
            PType ptype = ((DType.Primitive) dtype).ptype();
            int dtypeSize = dtypeSize(ptype);
            long[] latents = toLatents(ptype, data);
            int n = latents.length;

            if (n == 0) {
                return encodeEmpty(ctx.arena());
            }

            long[] sortKeys = toSortKeys(latents);

            // Build histogram on sorted latents
            long[] sortedKeys = sortKeys.clone();
            Arrays.sort(sortedKeys);
            int nBinsLog = n == 1 ? 0 : Math.min(N_BINS_LOG, 64 - Long.numberOfLeadingZeros(n - 1));
            List<PcoHistBin> histBins = buildHistogram(sortedKeys, n, nBinsLog);

            int nLogCeil = n <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(n - 1);
            int maxSizeLog = Math.min(Math.min(nBinsLog + 2, 12), nLogCeil);

            // NoOp path
            List<PcoBinOptimizer.Bin> noOpBins = PcoBinOptimizer.optimize(histBins, maxSizeLog, dtypeSize);
            float noOpCost = dpCost(noOpBins, n);
            PcoWeightQuantizer.Result noOpQ = quantize(noOpBins, n, maxSizeLog);

            // Consecutive delta path (only if n > 1)
            boolean useDelta = false;
            long[] deltas = null;
            List<PcoBinOptimizer.Bin> deltaBins = null;
            PcoWeightQuantizer.Result deltaQ = null;

            if (n > 1) {
                deltas = consecutiveDeltas(latents, dtypeSize);
                long[] deltaSortKeys = toSortKeys(deltas);
                long[] deltaSorted = deltaSortKeys.clone();
                Arrays.sort(deltaSorted);
                int dNBinsLog = Math.min(N_BINS_LOG, 64 - Long.numberOfLeadingZeros(n - 2));
                List<PcoHistBin> deltaHist = buildHistogram(deltaSorted, n - 1, dNBinsLog);
                int dMaxSizeLog = Math.min(Math.min(dNBinsLog + 2, 12), nLogCeil);
                deltaBins = PcoBinOptimizer.optimize(deltaHist, dMaxSizeLog, dtypeSize);
                float deltaCost = dtypeSize + dpCost(deltaBins, n - 1);
                if (deltaCost < noOpCost) {
                    useDelta = true;
                    deltaQ = quantize(deltaBins, n - 1, dMaxSizeLog);
                }
            }

            MemorySegment chunkMetaSeg;
            MemorySegment pageSeg;

            if (useDelta) {
                PcoAnsEncoder ansEncoder = PcoAnsEncoder.build(deltaQ.sizeLog(), deltaQ.weights());
                chunkMetaSeg = buildChunkMeta(dtypeSize, deltaBins, deltaQ, 1, 1, ctx.arena());
                pageSeg = buildPage(deltas, toSortKeys(deltas), deltaBins,
                        deltaQ.sizeLog(), ansEncoder, dtypeSize, latents[0], true, ctx.arena());
            } else {
                PcoAnsEncoder ansEncoder = PcoAnsEncoder.build(noOpQ.sizeLog(), noOpQ.weights());
                chunkMetaSeg = buildChunkMeta(dtypeSize, noOpBins, noOpQ, 0, 0, ctx.arena());
                pageSeg = buildPage(latents, sortKeys, noOpBins,
                        noOpQ.sizeLog(), ansEncoder, dtypeSize, 0L, false, ctx.arena());
            }

            ByteBuffer metaBuf = buildMetadata(n);
            EncodeNode node = new EncodeNode(EncodingId.VORTEX_PCO, metaBuf, new EncodeNode[0], new int[]{0, 1});
            return new EncodeResult(node, List.of(chunkMetaSeg, pageSeg), null, null);
        }

        // ── histogram ─────────────────────────────────────────────────────────

        private static List<PcoHistBin> buildHistogram(long[] sortedKeys, int n, int nBinsLog) {
            if (n == 0) {
                return List.of();
            }
            int nBins = 1 << nBinsLog;
            List<PcoHistBin> bins = new ArrayList<>(nBins);
            int start = 0;
            for (int b = 0; b < nBins && start < n; b++) {
                // ceiling of (b+1)*n/nBins
                int targetEnd = (int) (((long) (b + 1) * n + nBins - 1) >> nBinsLog);
                targetEnd = Math.min(targetEnd, n);
                // extend past equal values at boundary to keep same value in one bin
                while (targetEnd < n && sortedKeys[targetEnd] == sortedKeys[targetEnd - 1]) {
                    targetEnd++;
                }
                int end = Math.min(targetEnd, n);
                bins.add(new PcoHistBin(sortedKeys[start], sortedKeys[end - 1], end - start));
                start = end;
            }
            return bins;
        }

        // ── chunk meta ────────────────────────────────────────────────────────

        private static MemorySegment buildChunkMeta(
                int dtypeSize, List<PcoBinOptimizer.Bin> bins, PcoWeightQuantizer.Result qw,
                int deltaVariant, int deltaOrder, Arena arena) {
            int ansSizeLog = qw.sizeLog();
            int[] weights = qw.weights();
            LeBitWriter w = new LeBitWriter(64);
            w.writeBits(0, 4);             // mode = Classic (0)
            w.writeBits(deltaVariant, 4);  // 0=NoOp, 1=Consecutive
            if (deltaVariant == 1) {
                w.writeBits(deltaOrder, 3);
                w.writeBits(0, 1);         // secondaryUsesDelta = false
            }
            w.writeBits(ansSizeLog, 4);
            w.writeBits(bins.size(), 15);
            int bitsForWeight = ansSizeLog;
            int offsetBitsWidth = bitsToEncodeOffsetBits(dtypeSize);
            for (int i = 0; i < bins.size(); i++) {
                PcoBinOptimizer.Bin bin = bins.get(i);
                w.writeBits(weights[i] - 1, bitsForWeight); // quantized ANS weight, not raw count
                w.writeBits(bin.lowerLatent(), dtypeSize);
                w.writeBits(bin.offsetBits(), offsetBitsWidth);
            }
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        // ── page encoding ─────────────────────────────────────────────────────

        private static MemorySegment buildPage(
                long[] values, long[] valueSortKeys,
                List<PcoBinOptimizer.Bin> bins, int ansSizeLog,
                PcoAnsEncoder ansEncoder, int dtypeSize,
                long moment, boolean hasMoment, Arena arena) {

            int n = values.length;

            // Pre-compute bin lower sort keys for binary search
            long[] binLowers = new long[bins.size()];
            for (int i = 0; i < bins.size(); i++) {
                binLowers[i] = bins.get(i).lowerSortKey();
            }

            // Assign each value to a bin symbol and compute offset
            int[] symbols = new int[n];
            long[] offsets = new long[n];
            for (int i = 0; i < n; i++) {
                int sym = findBin(valueSortKeys[i], binLowers);
                symbols[i] = sym;
                offsets[i] = valueSortKeys[i] - binLowers[sym]; // = latent - bin.lowerLatent
            }

            // Compute bin offsetBits per symbol
            int[] binOffsetBits = new int[bins.size()];
            for (int i = 0; i < bins.size(); i++) {
                binOffsetBits[i] = bins.get(i).offsetBits();
            }

            // ANS encode LIFO: process elements in reverse, collect (bits,numBits) per batch
            int nBatches = (n + BATCH_N - 1) / BATCH_N;
            long[][] batchBits = new long[nBatches][];
            int[][] batchNumBits = new int[nBatches][];

            int[] states = new int[ANS_INTERLEAVING];
            Arrays.fill(states, ansEncoder.defaultState());

            for (int i = n - 1; i >= 0; i--) {
                int batch = i / BATCH_N;
                int posInBatch = i % BATCH_N;
                int stream = i % ANS_INTERLEAVING;

                if (batchBits[batch] == null) {
                    int batchSize = Math.min(BATCH_N, n - batch * BATCH_N);
                    batchBits[batch] = new long[batchSize];
                    batchNumBits[batch] = new int[batchSize];
                }

                PcoAnsEncoder.Step step = ansEncoder.encode(states[stream], symbols[i]);
                // posInBatch counts within batch (already in reverse order, will be reversed below)
                batchBits[batch][posInBatch] = step.bits();
                batchNumBits[batch][posInBatch] = step.numBits();
                states[stream] = step.newState();
            }

            // Final encoder states → decoder initial state indices (written to page header)
            int[] initialStateIdxs = new int[ANS_INTERLEAVING];
            for (int i = 0; i < ANS_INTERLEAVING; i++) {
                initialStateIdxs[i] = ansEncoder.toStateIdx(states[i]);
            }

            // Estimate page size
            long headerBits = (hasMoment ? dtypeSize : 0) + (long) ANS_INTERLEAVING * ansSizeLog;
            long ansTotalBits = 0;
            long offsetTotalBits = 0;
            for (int i = 0; i < n; i++) {
                ansTotalBits += batchNumBits[i / BATCH_N][i % BATCH_N];
                offsetTotalBits += binOffsetBits[symbols[i]];
            }
            long pageSizeBytes = (headerBits + ansTotalBits + offsetTotalBits + 7) / 8 + 8;
            LeBitWriter w = new LeBitWriter((int) Math.min(pageSizeBytes, Integer.MAX_VALUE));

            // Write page header
            if (hasMoment) {
                w.writeBits(moment, dtypeSize);
            }
            for (int i = 0; i < ANS_INTERLEAVING; i++) {
                w.writeBits(initialStateIdxs[i], ansSizeLog);
            }
            w.alignToByte();

            // Write per-batch: ANS bits (reversed within batch) then offset bits
            for (int b = 0; b < nBatches; b++) {
                int batchStart = b * BATCH_N;
                int batchSize = Math.min(BATCH_N, n - batchStart);

                // ANS bits were collected in reverse posInBatch order; write in forward order
                // (posInBatch 0 was encoded last → bits[0] is the correct forward value)
                for (int k = 0; k < batchSize; k++) {
                    w.writeBits(batchBits[b][k], batchNumBits[b][k]);
                }
                // Offset bits in forward order
                for (int k = 0; k < batchSize; k++) {
                    int i = batchStart + k;
                    w.writeBits(offsets[i], binOffsetBits[symbols[i]]);
                }
            }
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        // Binary search: find the bin whose range contains sortKey.
        private static int findBin(long sortKey, long[] binLowers) {
            int lo = 0;
            int hi = binLowers.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (binLowers[mid] <= sortKey) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo;
        }

        // ── DP cost estimate ─────────────────────────────────────────────────

        // Approximate total compressed bits from the bin optimizer output.
        private static float dpCost(List<PcoBinOptimizer.Bin> bins, int n) {
            if (bins.isEmpty()) {
                return 0f;
            }
            float totalLog2 = PcoBinOptimizer.log2Approx((float) n);
            float cost = 0f;
            for (PcoBinOptimizer.Bin bin : bins) {
                float ansLoss = totalLog2 - PcoBinOptimizer.log2Approx((float) bin.weight());
                cost += (ansLoss + bin.offsetBits()) * bin.weight();
            }
            return cost;
        }

        // ── delta computation ────────────────────────────────────────────────

        private static long[] consecutiveDeltas(long[] latents, int dtypeSize) {
            long mid = typeMid(dtypeSize);
            long mask = typeMask(dtypeSize);
            long[] deltas = new long[latents.length - 1];
            for (int i = 0; i < deltas.length; i++) {
                deltas[i] = ((latents[i + 1] - latents[i]) & mask) ^ mid;
            }
            return deltas;
        }

        // ── sort-key conversion ──────────────────────────────────────────────

        // Latent → signed sort key so Arrays.sort gives unsigned latent order.
        private static long[] toSortKeys(long[] latents) {
            long[] keys = new long[latents.length];
            for (int i = 0; i < latents.length; i++) {
                keys[i] = latents[i] ^ Long.MIN_VALUE;
            }
            return keys;
        }

        // ── weight quantization ───────────────────────────────────────────────

        private static PcoWeightQuantizer.Result quantize(
                List<PcoBinOptimizer.Bin> bins, int totalCount, int maxSizeLog) {
            int[] counts = new int[bins.size()];
            for (int i = 0; i < counts.length; i++) {
                counts[i] = bins.get(i).weight();
            }
            return PcoWeightQuantizer.quantize(counts, totalCount, maxSizeLog);
        }

        // ── metadata ─────────────────────────────────────────────────────────

        private static EncodeResult encodeEmpty(Arena arena) {
            byte[] header = {PCO_FORMAT_MAJOR, PCO_FORMAT_MINOR};
            PcoMetadata meta = new PcoMetadata(header, List.of());
            ByteBuffer metaBuf = ByteBuffer.wrap(meta.encode());
            EncodeNode node = new EncodeNode(EncodingId.VORTEX_PCO, metaBuf, new EncodeNode[0], new int[0]);
            return new EncodeResult(node, List.of(), null, null);
        }

        private static ByteBuffer buildMetadata(int n) {
            byte[] header = {PCO_FORMAT_MAJOR, PCO_FORMAT_MINOR};
            PcoChunkInfo chunkInfo = new PcoChunkInfo(List.of(new PcoPageInfo(n)));
            PcoMetadata meta = new PcoMetadata(header, List.of(chunkInfo));
            return ByteBuffer.wrap(meta.encode());
        }

        // ── latent conversion ─────────────────────────────────────────────────

        private static long[] toLatents(PType ptype, Object data) {
            return switch (ptype) {
                case I16 -> {
                    short[] arr = (short[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        l[i] = (arr[i] & 0xFFFFL) ^ 0x8000L;
                    }
                    yield l;
                }
                case U16 -> {
                    short[] arr = (short[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        l[i] = arr[i] & 0xFFFFL;
                    }
                    yield l;
                }
                case I32 -> {
                    int[] arr = (int[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        l[i] = (arr[i] & 0xFFFFFFFFL) ^ 0x80000000L;
                    }
                    yield l;
                }
                case U32 -> {
                    int[] arr = (int[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        l[i] = arr[i] & 0xFFFFFFFFL;
                    }
                    yield l;
                }
                case I64 -> {
                    long[] arr = (long[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        l[i] = arr[i] ^ Long.MIN_VALUE;
                    }
                    yield l;
                }
                case U64 -> {
                    long[] arr = (long[]) data;
                    long[] l = new long[arr.length];
                    System.arraycopy(arr, 0, l, 0, arr.length);
                    yield l;
                }
                case F32 -> {
                    float[] arr = (float[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        int bits = Float.floatToRawIntBits(arr[i]);
                        l[i] = (bits & 0x80000000) != 0
                                ? (~bits) & 0xFFFFFFFFL
                                : (bits ^ 0x80000000) & 0xFFFFFFFFL;
                    }
                    yield l;
                }
                case F64 -> {
                    double[] arr = (double[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        long bits = Double.doubleToRawLongBits(arr[i]);
                        l[i] = (bits & Long.MIN_VALUE) != 0 ? ~bits : bits ^ Long.MIN_VALUE;
                    }
                    yield l;
                }
                default -> throw new VortexException(EncodingId.VORTEX_PCO, "unsupported ptype: " + ptype);
            };
        }

        // ── helpers ───────────────────────────────────────────────────────────

        private static int dtypeSize(PType ptype) {
            return switch (ptype) {
                case I16, U16 -> 16;
                case I32, U32, F32 -> 32;
                case I64, U64, F64 -> 64;
                default -> throw new VortexException(EncodingId.VORTEX_PCO, "unsupported ptype: " + ptype);
            };
        }

        private static int bitsToEncodeOffsetBits(int dtypeSize) {
            return switch (dtypeSize) {
                case 64 -> 7;
                case 32 -> 6;
                case 16 -> 5;
                default -> throw new VortexException(EncodingId.VORTEX_PCO, "invalid dtypeSize: " + dtypeSize);
            };
        }

        private static long typeMid(int dtypeSize) {
            return switch (dtypeSize) {
                case 64 -> Long.MIN_VALUE;
                case 32 -> 0x80000000L;
                case 16 -> 0x8000L;
                default -> throw new VortexException(EncodingId.VORTEX_PCO, "invalid dtypeSize: " + dtypeSize);
            };
        }

        private static long typeMask(int dtypeSize) {
            return switch (dtypeSize) {
                case 64 -> -1L;
                case 32 -> 0xFFFFFFFFL;
                case 16 -> 0xFFFFL;
                default -> throw new VortexException(EncodingId.VORTEX_PCO, "invalid dtypeSize: " + dtypeSize);
            };
        }
    }
}
