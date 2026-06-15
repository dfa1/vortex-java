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
import java.util.OptionalLong;
import java.util.stream.IntStream;

/// Write-only encoder for {@code vortex.pco}.
///
/// Supports Classic mode (mode=0) and IntMult mode (mode=1).
/// Each chunk independently chooses:
/// - IntMult base (via triple-GCD detection) if integer data has a structural multiplier
/// - Otherwise Classic mode with NoOp or Consecutive delta (deltaVariant=0 or 1)
/// Data is split into chunks of {@value #CHUNK_SIZE} elements.
public final class PcoEncodingEncoder implements EncodingEncoder {

    private static final byte PCO_FORMAT_MAJOR = 0x04;
    private static final byte PCO_FORMAT_MINOR = 0x01;
    private static final int BATCH_N = 256;
    private static final int ANS_INTERLEAVING = 4;
    private static final int N_BINS_LOG = 8;
    private static final int CHUNK_SIZE = 1 << 16; // 65 536 elements

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

        private record ChunkResult(MemorySegment chunkMeta, MemorySegment page, int pageN) {
        }

        // Per-stream encoded ANS state. Used for both single-stream (Classic) and dual-stream (IntMult) modes.
        @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
        private record StreamData(
                int[] symbols,
                long[] offsets,
                int[] binOffsetBits,
                int ansSizeLog,
                long[][] batchBits,
                int[][] batchNumBits,
                int[] initialStateIdxs
        ) {
        }

        static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
            PType ptype = ((DType.Primitive) dtype).ptype();
            int dtypeSize = dtypeSize(ptype);
            long[] allLatents = toLatents(ptype, data);
            int n = allLatents.length;

            if (n == 0) {
                return encodeEmpty(ctx.arena());
            }

            List<MemorySegment> chunkMetas = new ArrayList<>();
            List<MemorySegment> pages = new ArrayList<>();
            List<PcoChunkInfo> chunks = new ArrayList<>();

            int chunkStart = 0;
            while (chunkStart < n) {
                int chunkEnd = Math.min(chunkStart + CHUNK_SIZE, n);
                long[] chunkLatents = Arrays.copyOfRange(allLatents, chunkStart, chunkEnd);
                ChunkResult result = encodeChunk(chunkLatents, ptype, dtypeSize, ctx.arena());
                chunkMetas.add(result.chunkMeta());
                pages.add(result.page());
                chunks.add(new PcoChunkInfo(List.of(new PcoPageInfo(result.pageN()))));
                chunkStart = chunkEnd;
            }

            List<MemorySegment> buffers = new ArrayList<>(chunkMetas);
            buffers.addAll(pages);
            int[] allBufIdxs = IntStream.range(0, buffers.size()).toArray();
            ByteBuffer metaBuf = buildMetadata(chunks);
            EncodeNode node = new EncodeNode(EncodingId.VORTEX_PCO, metaBuf, new EncodeNode[0], allBufIdxs);
            return new EncodeResult(node, buffers, null, null);
        }

        private static ChunkResult encodeChunk(long[] latents, PType ptype, int dtypeSize, Arena arena) {
            int n = latents.length;

            // Try IntMult mode for integer dtypes — split into (mult, adj) and check if it wins vs Classic.
            if (isIntegerPtype(ptype) && n >= 10) {
                OptionalLong baseOpt = PcoIntMultDetector.choose(latents, dtypeSize);
                if (baseOpt.isPresent()) {
                    ChunkResult intMult = tryEncodeIntMult(latents, baseOpt.getAsLong(), dtypeSize, arena);
                    if (intMult != null) {
                        return intMult;
                    }
                }
            }

            return encodeChunkClassic(latents, dtypeSize, arena);
        }

        private static boolean isIntegerPtype(PType ptype) {
            return switch (ptype) {
                case I16, U16, I32, U32, I64, U64 -> true;
                default -> false;
            };
        }

        // ── Classic mode (with NoOp or Consecutive delta) ─────────────────────

        private static ChunkResult encodeChunkClassic(long[] latents, int dtypeSize, Arena arena) {
            int n = latents.length;
            long[] sortKeys = toSortKeys(latents);

            long[] sortedKeys = sortKeys.clone();
            Arrays.sort(sortedKeys);
            int nBinsLog = n == 1 ? 0 : Math.min(N_BINS_LOG, 64 - Long.numberOfLeadingZeros((long) n - 1));
            List<PcoHistBin> histBins = buildHistogram(sortedKeys, n, nBinsLog);

            int nLogCeil = n <= 1 ? 0 : 64 - Long.numberOfLeadingZeros((long) n - 1);
            int maxSizeLog = Math.min(Math.min(nBinsLog + 2, 12), nLogCeil);

            List<PcoBinOptimizer.Bin> noOpBins = PcoBinOptimizer.optimize(histBins, maxSizeLog, dtypeSize);
            float noOpCost = dpCost(noOpBins, n);
            PcoWeightQuantizer.Result noOpQ = quantize(noOpBins, n, maxSizeLog);

            boolean useDelta = false;
            long[] deltas = null;
            List<PcoBinOptimizer.Bin> deltaBins = null;
            PcoWeightQuantizer.Result deltaQ = null;

            if (n > 1) {
                deltas = consecutiveDeltas(latents, dtypeSize);
                long[] deltaSorted = toSortKeys(deltas).clone();
                Arrays.sort(deltaSorted);
                int dNBinsLog = Math.min(N_BINS_LOG, 64 - Long.numberOfLeadingZeros((long) n - 2));
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
                chunkMetaSeg = buildClassicChunkMeta(dtypeSize, deltaBins, deltaQ, 1, 1, arena);
                pageSeg = buildClassicPage(deltas, toSortKeys(deltas), deltaBins,
                        deltaQ.sizeLog(), ansEncoder, dtypeSize, latents[0], true, arena);
            } else {
                PcoAnsEncoder ansEncoder = PcoAnsEncoder.build(noOpQ.sizeLog(), noOpQ.weights());
                chunkMetaSeg = buildClassicChunkMeta(dtypeSize, noOpBins, noOpQ, 0, 0, arena);
                pageSeg = buildClassicPage(latents, sortKeys, noOpBins,
                        noOpQ.sizeLog(), ansEncoder, dtypeSize, 0L, false, arena);
            }

            return new ChunkResult(chunkMetaSeg, pageSeg, n);
        }

        // ── IntMult mode (mode=1, NoOp delta on both streams) ─────────────────

        // Returns null if IntMult fails to actually beat Classic after full bin optimization.
        private static ChunkResult tryEncodeIntMult(long[] latents, long base, int dtypeSize, Arena arena) {
            int n = latents.length;
            long mask = typeMask(dtypeSize);
            long[] mults = new long[n];
            long[] adjs = new long[n];
            for (int i = 0; i < n; i++) {
                mults[i] = Long.divideUnsigned(latents[i], base);
                adjs[i] = Long.remainderUnsigned(latents[i], base);
            }

            // Train bins for both streams independently.
            int nLogCeil = n <= 1 ? 0 : 64 - Long.numberOfLeadingZeros((long) n - 1);
            int nBinsLog = n == 1 ? 0 : Math.min(N_BINS_LOG, 64 - Long.numberOfLeadingZeros((long) n - 1));
            int maxSizeLog = Math.min(Math.min(nBinsLog + 2, 12), nLogCeil);

            long[] multSortKeys = toSortKeys(mults);
            long[] multSorted = multSortKeys.clone();
            Arrays.sort(multSorted);
            List<PcoHistBin> multHist = buildHistogram(multSorted, n, nBinsLog);
            List<PcoBinOptimizer.Bin> multBins = PcoBinOptimizer.optimize(multHist, maxSizeLog, dtypeSize);
            PcoWeightQuantizer.Result multQ = quantize(multBins, n, maxSizeLog);

            long[] adjSortKeys = toSortKeys(adjs);
            long[] adjSorted = adjSortKeys.clone();
            Arrays.sort(adjSorted);
            List<PcoHistBin> adjHist = buildHistogram(adjSorted, n, nBinsLog);
            List<PcoBinOptimizer.Bin> adjBins = PcoBinOptimizer.optimize(adjHist, maxSizeLog, dtypeSize);
            PcoWeightQuantizer.Result adjQ = quantize(adjBins, n, maxSizeLog);

            // Sanity: only use IntMult if combined cost actually beats Classic.
            float classicEstCost = estimateClassicCost(latents, dtypeSize, maxSizeLog);
            float intMultCost = dpCost(multBins, n) + dpCost(adjBins, n);
            if (intMultCost >= classicEstCost) {
                return null;
            }

            PcoAnsEncoder multAnsEncoder = PcoAnsEncoder.build(multQ.sizeLog(), multQ.weights());
            PcoAnsEncoder adjAnsEncoder = PcoAnsEncoder.build(adjQ.sizeLog(), adjQ.weights());

            MemorySegment chunkMetaSeg = buildIntMultChunkMeta(dtypeSize, base, multBins, multQ, adjBins, adjQ, arena);
            MemorySegment pageSeg = buildIntMultPage(
                    mults, multSortKeys, multBins, multQ.sizeLog(), multAnsEncoder,
                    adjs, adjSortKeys, adjBins, adjQ.sizeLog(), adjAnsEncoder,
                    arena);
            return new ChunkResult(chunkMetaSeg, pageSeg, n);
        }

        // Estimates the best Classic-mode cost, considering both NoOp and Consecutive delta paths.
        // IntMult should only win if it beats whichever Classic variant would be picked.
        private static float estimateClassicCost(long[] latents, int dtypeSize, int maxSizeLog) {
            int n = latents.length;
            long[] sortKeys = toSortKeys(latents);
            long[] sorted = sortKeys.clone();
            Arrays.sort(sorted);
            int nBinsLog = n == 1 ? 0 : Math.min(N_BINS_LOG, 64 - Long.numberOfLeadingZeros((long) n - 1));
            List<PcoHistBin> hist = buildHistogram(sorted, n, nBinsLog);
            List<PcoBinOptimizer.Bin> bins = PcoBinOptimizer.optimize(hist, maxSizeLog, dtypeSize);
            float noOpCost = dpCost(bins, n);

            if (n <= 1) {
                return noOpCost;
            }
            long[] deltas = consecutiveDeltas(latents, dtypeSize);
            long[] dSorted = toSortKeys(deltas).clone();
            Arrays.sort(dSorted);
            int dNBinsLog = Math.min(N_BINS_LOG, 64 - Long.numberOfLeadingZeros((long) n - 2));
            List<PcoHistBin> dHist = buildHistogram(dSorted, n - 1, dNBinsLog);
            List<PcoBinOptimizer.Bin> dBins = PcoBinOptimizer.optimize(dHist, maxSizeLog, dtypeSize);
            float deltaCost = dtypeSize + dpCost(dBins, n - 1);
            return Math.min(noOpCost, deltaCost);
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
                int targetEnd = (int) (((long) (b + 1) * n + nBins - 1) >> nBinsLog);
                targetEnd = Math.min(targetEnd, n);
                while (targetEnd < n && sortedKeys[targetEnd] == sortedKeys[targetEnd - 1]) {
                    targetEnd++;
                }
                int end = Math.min(targetEnd, n);
                bins.add(new PcoHistBin(sortedKeys[start], sortedKeys[end - 1], end - start));
                start = end;
            }
            return bins;
        }

        // ── Classic chunk meta ────────────────────────────────────────────────

        private static MemorySegment buildClassicChunkMeta(
                int dtypeSize, List<PcoBinOptimizer.Bin> bins, PcoWeightQuantizer.Result qw,
                int deltaVariant, int deltaOrder, Arena arena) {
            int ansSizeLog = qw.sizeLog();
            int[] weights = qw.weights();
            LeBitWriter w = new LeBitWriter(64);
            w.writeBits(0, 4);            // mode = Classic
            w.writeBits(deltaVariant, 4); // 0=NoOp or 1=Consecutive
            if (deltaVariant == 1) {
                w.writeBits(deltaOrder, 3);
                w.writeBits(0, 1);        // secondaryUsesDelta=false (no secondary in Classic)
            }
            w.writeBits(ansSizeLog, 4);
            w.writeBits(bins.size(), 15);
            int offsetBitsWidth = bitsToEncodeOffsetBits(dtypeSize);
            for (int i = 0; i < bins.size(); i++) {
                PcoBinOptimizer.Bin bin = bins.get(i);
                w.writeBits(weights[i] - 1L, ansSizeLog);
                w.writeBits(bin.lowerLatent(), dtypeSize);
                w.writeBits(bin.offsetBits(), offsetBitsWidth);
            }
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        // ── IntMult chunk meta ────────────────────────────────────────────────

        private static MemorySegment buildIntMultChunkMeta(
                int dtypeSize, long base,
                List<PcoBinOptimizer.Bin> primaryBins, PcoWeightQuantizer.Result primaryQ,
                List<PcoBinOptimizer.Bin> secondaryBins, PcoWeightQuantizer.Result secondaryQ,
                Arena arena) {
            int primaryAnsSizeLog = primaryQ.sizeLog();
            int[] primaryWeights = primaryQ.weights();
            int secondaryAnsSizeLog = secondaryQ.sizeLog();
            int[] secondaryWeights = secondaryQ.weights();
            int offsetBitsWidth = bitsToEncodeOffsetBits(dtypeSize);

            LeBitWriter w = new LeBitWriter(128);
            w.writeBits(1, 4);                 // mode = IntMult
            w.writeBits(base, dtypeSize);      // base value
            w.writeBits(0, 4);                 // deltaVariant = NoOp
            // (no deltaOrder/secondaryUsesDelta bits when deltaVariant=0)

            // Primary latent var meta
            w.writeBits(primaryAnsSizeLog, 4);
            w.writeBits(primaryBins.size(), 15);
            for (int i = 0; i < primaryBins.size(); i++) {
                PcoBinOptimizer.Bin bin = primaryBins.get(i);
                w.writeBits(primaryWeights[i] - 1L, primaryAnsSizeLog);
                w.writeBits(bin.lowerLatent(), dtypeSize);
                w.writeBits(bin.offsetBits(), offsetBitsWidth);
            }

            // Secondary latent var meta
            w.writeBits(secondaryAnsSizeLog, 4);
            w.writeBits(secondaryBins.size(), 15);
            for (int i = 0; i < secondaryBins.size(); i++) {
                PcoBinOptimizer.Bin bin = secondaryBins.get(i);
                w.writeBits(secondaryWeights[i] - 1L, secondaryAnsSizeLog);
                w.writeBits(bin.lowerLatent(), dtypeSize);
                w.writeBits(bin.offsetBits(), offsetBitsWidth);
            }
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        // ── Classic page encoding ─────────────────────────────────────────────

        private static MemorySegment buildClassicPage(
                long[] values, long[] valueSortKeys,
                List<PcoBinOptimizer.Bin> bins, int ansSizeLog,
                PcoAnsEncoder ansEncoder, int dtypeSize,
                long moment, boolean hasMoment, Arena arena) {

            int n = values.length;
            StreamData stream = prepareStream(valueSortKeys, bins, ansSizeLog, ansEncoder);

            long headerBits = (hasMoment ? dtypeSize : 0) + (long) ANS_INTERLEAVING * ansSizeLog;
            long payloadBits = streamPayloadBits(stream);
            long pageSizeBytes = (headerBits + payloadBits + 7) / 8 + 16;
            LeBitWriter w = new LeBitWriter((int) Math.min(pageSizeBytes, Integer.MAX_VALUE));

            if (hasMoment) {
                w.writeBits(moment, dtypeSize);
            }
            for (int i = 0; i < ANS_INTERLEAVING; i++) {
                w.writeBits(stream.initialStateIdxs()[i], ansSizeLog);
            }
            w.alignToByte();

            writeStream(w, stream, n);
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        // ── IntMult page encoding ─────────────────────────────────────────────

        private static MemorySegment buildIntMultPage(
                long[] mults, long[] multSortKeys, List<PcoBinOptimizer.Bin> multBins,
                int multAnsSizeLog, PcoAnsEncoder multAnsEncoder,
                long[] adjs, long[] adjSortKeys, List<PcoBinOptimizer.Bin> adjBins,
                int adjAnsSizeLog, PcoAnsEncoder adjAnsEncoder,
                Arena arena) {

            int n = mults.length;
            StreamData primary = prepareStream(multSortKeys, multBins, multAnsSizeLog, multAnsEncoder);
            StreamData secondary = prepareStream(adjSortKeys, adjBins, adjAnsSizeLog, adjAnsEncoder);

            // Header: primary states (4), secondary states (4). No moments (deltaOrder=0 on both).
            long headerBits = (long) ANS_INTERLEAVING * (multAnsSizeLog + adjAnsSizeLog);
            long payloadBits = streamPayloadBits(primary) + streamPayloadBits(secondary);
            long pageSizeBytes = (headerBits + payloadBits + 7) / 8 + 16;
            LeBitWriter w = new LeBitWriter((int) Math.min(pageSizeBytes, Integer.MAX_VALUE));

            // Primary moments: deltaOrder=0 → none
            for (int i = 0; i < ANS_INTERLEAVING; i++) {
                w.writeBits(primary.initialStateIdxs()[i], multAnsSizeLog);
            }
            // Secondary moments: secondaryDeltaOrder=0 → none
            for (int i = 0; i < ANS_INTERLEAVING; i++) {
                w.writeBits(secondary.initialStateIdxs()[i], adjAnsSizeLog);
            }
            w.alignToByte();

            // Per-batch: primary ANS + primary offsets, then secondary ANS + secondary offsets.
            int nBatches = (n + BATCH_N - 1) / BATCH_N;
            for (int b = 0; b < nBatches; b++) {
                int batchStart = b * BATCH_N;
                int batchSize = Math.min(BATCH_N, n - batchStart);
                writeBatch(w, primary, b, batchStart, batchSize);
                writeBatch(w, secondary, b, batchStart, batchSize);
            }
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        // ── Stream preparation: ANS encode LIFO, collect bits per batch ──────

        private static StreamData prepareStream(
                long[] sortKeys, List<PcoBinOptimizer.Bin> bins, int ansSizeLog, PcoAnsEncoder ansEncoder) {
            int n = sortKeys.length;
            long[] binLowers = new long[bins.size()];
            int[] binOffsetBits = new int[bins.size()];
            for (int i = 0; i < bins.size(); i++) {
                binLowers[i] = bins.get(i).lowerSortKey();
                binOffsetBits[i] = bins.get(i).offsetBits();
            }

            int[] symbols = new int[n];
            long[] offsets = new long[n];
            for (int i = 0; i < n; i++) {
                int sym = findBin(sortKeys[i], binLowers);
                symbols[i] = sym;
                offsets[i] = sortKeys[i] - binLowers[sym];
            }

            int nBatches = (n + BATCH_N - 1) / BATCH_N;
            long[][] batchBits = new long[nBatches][];
            int[][] batchNumBits = new int[nBatches][];

            int[] states = new int[ANS_INTERLEAVING];
            Arrays.fill(states, ansEncoder.defaultState());

            for (int i = n - 1; i >= 0; i--) {
                int batch = i / BATCH_N;
                int posInBatch = i % BATCH_N;
                int strm = i % ANS_INTERLEAVING;

                if (batchBits[batch] == null) {
                    int batchSize = Math.min(BATCH_N, n - batch * BATCH_N);
                    batchBits[batch] = new long[batchSize];
                    batchNumBits[batch] = new int[batchSize];
                }

                PcoAnsEncoder.Step step = ansEncoder.encode(states[strm], symbols[i]);
                batchBits[batch][posInBatch] = step.bits();
                batchNumBits[batch][posInBatch] = step.numBits();
                states[strm] = step.newState();
            }

            int[] initialStateIdxs = new int[ANS_INTERLEAVING];
            for (int i = 0; i < ANS_INTERLEAVING; i++) {
                initialStateIdxs[i] = ansEncoder.toStateIdx(states[i]);
            }

            return new StreamData(symbols, offsets, binOffsetBits, ansSizeLog,
                    batchBits, batchNumBits, initialStateIdxs);
        }

        private static long streamPayloadBits(StreamData s) {
            long ansBits = 0;
            long offsetBits = 0;
            int n = s.symbols().length;
            for (int i = 0; i < n; i++) {
                ansBits += s.batchNumBits()[i / BATCH_N][i % BATCH_N];
                offsetBits += s.binOffsetBits()[s.symbols()[i]];
            }
            return ansBits + offsetBits;
        }

        private static void writeStream(LeBitWriter w, StreamData s, int n) {
            int nBatches = (n + BATCH_N - 1) / BATCH_N;
            for (int b = 0; b < nBatches; b++) {
                int batchStart = b * BATCH_N;
                int batchSize = Math.min(BATCH_N, n - batchStart);
                writeBatch(w, s, b, batchStart, batchSize);
            }
        }

        private static void writeBatch(LeBitWriter w, StreamData s, int batchIdx, int batchStart, int batchSize) {
            // Phase 1: ANS bits in forward order
            for (int k = 0; k < batchSize; k++) {
                w.writeBits(s.batchBits()[batchIdx][k], s.batchNumBits()[batchIdx][k]);
            }
            // Phase 2: offset bits in forward order
            for (int k = 0; k < batchSize; k++) {
                int i = batchStart + k;
                w.writeBits(s.offsets()[i], s.binOffsetBits()[s.symbols()[i]]);
            }
        }

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

        private static ByteBuffer buildMetadata(List<PcoChunkInfo> chunks) {
            byte[] header = {PCO_FORMAT_MAJOR, PCO_FORMAT_MINOR};
            PcoMetadata meta = new PcoMetadata(header, chunks);
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
