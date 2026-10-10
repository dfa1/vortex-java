package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoPcoChunkInfo;
import io.github.dfa1.vortex.core.proto.ProtoPcoMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPcoPageInfo;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.IntStream;

/// Write-only encoder for `vortex.pco`.
///
/// Supports Classic mode (mode=0) and IntMult mode (mode=1).
/// Each chunk independently chooses:
/// - IntMult base (via triple-GCD detection) if integer data has a structural multiplier
/// - Otherwise Classic mode with NoOp or Consecutive delta (deltaVariant=0 or 1)
/// Data is split into chunks of {@value #CHUNK_SIZE} elements, each written as pages of at most
/// {@value #MAX_PAGE_N}, the layout Vortex's Rust Pco scheme writes.
public final class PcoEncodingEncoder implements EncodingEncoder {

    private static final byte PCO_FORMAT_MAJOR = 0x04;
    private static final byte PCO_FORMAT_MINOR = 0x01;
    private static final int BATCH_N = 256;
    private static final int ANS_INTERLEAVING = 4;
    private static final int COMPRESSION_LEVEL = 8; // pco::DEFAULT_COMPRESSION_LEVEL, what Vortex passes
    private static final int LIMITED_UNOPTIMIZED_BINS_LOG = 6;
    // Vortex's VALUES_PER_CHUNK (pco::DEFAULT_MAX_PAGE_N) and the values_per_page its Pco scheme passes
    private static final int CHUNK_SIZE = 1 << 18;
    private static final int MAX_PAGE_N = 8192;
    // Rust's choose_auto_delta_encoding sample: groups of DELTA_GROUP_SIZE, one more per N_PER_EXTRA_DELTA_GROUP
    private static final int DELTA_GROUP_SIZE = 200;
    private static final int N_PER_EXTRA_DELTA_GROUP = 10_000;
    // Mode::Classic.max_bit_size() and DeltaEncoding::MAX_BIT_SIZE, in Rust's meta_size_hint
    private static final int CLASSIC_MODE_MAX_BITS = 4;
    private static final int DELTA_ENCODING_MAX_BITS = 4 + 5 + 5 + 64 + 32 * 32;

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
        // Pco is a lossless numeric codec: it reorders nothing and drops nothing, so the column's
        // bounds are the input's. Attached here rather than inside Encoder, which assembles the
        // result several frames deep from the values.
        return Encoder.encode(dtype, data, ctx).withStats(ZoneMapStats.of(dtype, data));
    }

    private static final class Encoder {

        private record ChunkResult(MemorySegment chunkMeta, List<MemorySegment> pages, int[] pageNs) {
        }

        private record Trained(List<PcoBinOptimizer.Bin> bins, PcoWeightQuantizer.Result q) {
        }

        static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
            PType ptype = ((DType.Primitive) dtype).ptype();
            int dtypeSize = dtypeSize(ptype);
            long[] allLatents = toLatents(ptype, data);
            int n = allLatents.length;

            if (n == 0) {
                return encodeEmpty();
            }

            List<EncodedBuffer> chunkMetas = new ArrayList<>();
            List<EncodedBuffer> pages = new ArrayList<>();
            List<ProtoPcoChunkInfo> chunks = new ArrayList<>();

            int chunkStart = 0;
            while (chunkStart < n) {
                int chunkEnd = Math.min(chunkStart + CHUNK_SIZE, n);
                long[] chunkLatents = Arrays.copyOfRange(allLatents, chunkStart, chunkEnd);
                ChunkResult result = encodeChunk(chunkLatents, ptype, dtypeSize, ctx.arena());
                chunkMetas.add(EncodedBuffer.bytes(result.chunkMeta()));
                List<ProtoPcoPageInfo> pageInfos = new ArrayList<>(result.pageNs().length);
                for (int p = 0; p < result.pageNs().length; p++) {
                    pages.add(EncodedBuffer.bytes(result.pages().get(p)));
                    pageInfos.add(new ProtoPcoPageInfo(result.pageNs()[p]));
                }
                chunks.add(new ProtoPcoChunkInfo(pageInfos));
                chunkStart = chunkEnd;
            }

            List<EncodedBuffer> buffers = new ArrayList<>(chunkMetas);
            buffers.addAll(pages);
            int[] allBufIdxs = IntStream.range(0, buffers.size()).toArray();
            MemorySegment metaBuf = buildMetadata(chunks);
            EncodeNode node = new EncodeNode(EncodingId.VORTEX_PCO, metaBuf, new EncodeNode[0], allBufIdxs);
            return new EncodeResult(node, buffers, null, null);
        }

        private static ChunkResult encodeChunk(long[] latents, PType ptype, int dtypeSize, Arena arena) {
            int n = latents.length;

            // IntMult mode for integer dtypes with a structural multiplier — split into (mult, adj).
            if (isIntegerPtype(ptype) && n >= 10) {
                OptionalLong baseOpt = PcoIntMultDetector.choose(latents);
                if (baseOpt.isPresent()) {
                    return encodeIntMult(latents, baseOpt.getAsLong(), dtypeSize, arena);
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
            int nBinsLog = unoptimizedBinsLog(n);
            boolean useDelta = n > 1 && chooseConsecutiveDelta(latents, dtypeSize, nBinsLog);
            int order = useDelta ? 1 : 0;
            int[] pageNs = pageNs(n);
            // Rust delta-encodes each page on its own: a page stores its first latent as the moment
            // and the deltas after it; bins are trained on every page's stored latents together.
            long[] sortKeys = toSortKeys(useDelta ? pageDeltas(latents, pageNs, dtypeSize) : latents);
            Trained t = train(sortKeys, nBinsLog, dtypeSize);

            MemorySegment chunkMetaSeg = buildClassicChunkMeta(dtypeSize, t.bins(), t.q(), order, order, arena);
            PageStream stream = new PageStream(t, pageNs[0]);
            LeBitWriter w = new LeBitWriter(pageNs[0] * (dtypeSize / 8) + 64);
            List<MemorySegment> pages = new ArrayList<>(pageNs.length);
            int pageStart = 0;
            int storedStart = 0;
            for (int pageN : pageNs) {
                int storedN = Math.max(pageN - order, 0);
                stream.prepare(sortKeys, storedStart, storedN);
                pages.add(buildClassicPage(w, stream, dtypeSize, latents[pageStart], useDelta, arena));
                pageStart += pageN;
                storedStart += storedN;
            }
            return new ChunkResult(chunkMetaSeg, pages, pageNs);
        }

        // Port of PagingSpec::EqualPagesUpTo: the fewest pages of at most MAX_PAGE_N, lengths within one.
        private static int[] pageNs(int n) {
            int nPages = (n + MAX_PAGE_N - 1) / MAX_PAGE_N;
            int low = n / nPages;
            int r = n % nPages;
            int[] pageNs = new int[nPages];
            for (int p = 0; p < nPages; p++) {
                pageNs[p] = p < r ? low + 1 : low;
            }
            return pageNs;
        }

        // Consecutive deltas within each page, pages concatenated (each page loses its first latent).
        private static long[] pageDeltas(long[] latents, int[] pageNs, int dtypeSize) {
            long[] out = new long[latents.length - pageNs.length];
            int pageStart = 0;
            int outPos = 0;
            for (int pageN : pageNs) {
                long[] deltas = consecutiveDeltas(Arrays.copyOfRange(latents, pageStart, pageStart + pageN), dtypeSize);
                System.arraycopy(deltas, 0, out, outPos, deltas.length);
                outPos += deltas.length;
                pageStart += pageN;
            }
            return out;
        }

        // Port of choose_unoptimized_bins_log: the compression level, halfway reduced for small chunks.
        private static int unoptimizedBinsLog(int n) {
            int logN = 31 - Integer.numberOfLeadingZeros(n);
            int fast = Math.max(logN - 4, 0);
            return COMPRESSION_LEVEL <= fast ? COMPRESSION_LEVEL : fast + (COMPRESSION_LEVEL - fast) / 2;
        }

        // ── delta choice (port of choose_auto_delta_encoding, NoOp vs Consecutive order 1) ──

        // Rust prices each delta candidate by fully compressing a sample of groups of 200 values
        // spread over the chunk, not by training on the whole chunk twice.
        private static boolean chooseConsecutiveDelta(long[] latents, int dtypeSize, int nBinsLog) {
            long[] sample = deltaSample(latents, DELTA_GROUP_SIZE, 1 + latents.length / N_PER_EXTRA_DELTA_GROUP);
            float noOpCost = compressedSampleSize(sample, 0, dtypeSize, nBinsLog);
            float deltaCost = compressedSampleSize(sample, 1, dtypeSize, nBinsLog);
            return deltaCost < noOpCost;
        }

        // Port of choose_delta_sample: the first group, then nExtraGroups more evenly spaced.
        private static long[] deltaSample(long[] latents, int groupSize, int nExtraGroups) {
            int n = latents.length;
            int nominal = (nExtraGroups + 1) * groupSize;
            int padding = Math.max(n - nominal, 0) / nExtraGroups;
            long[] sample = new long[Math.min(nominal, n)];
            int len = Math.min(groupSize, n);
            System.arraycopy(latents, 0, sample, 0, len);
            int i = groupSize;
            for (int g = 0; g < nExtraGroups; g++) {
                i += padding;
                int take = Math.clamp((long) n - i, 0, groupSize);
                System.arraycopy(latents, Math.min(i, n), sample, len, take);
                len += take;
                i += groupSize;
            }
            return len == sample.length ? sample : Arrays.copyOf(sample, len);
        }

        // Port of calculate_compressed_sample_size: chunk meta max size + page meta + body bytes.
        private static float compressedSampleSize(long[] sample, int order, int dtypeSize, int nBinsLog) {
            long[] values = order == 0 ? sample : consecutiveDeltas(sample, dtypeSize);
            int ansSizeLog = 0;
            long binBits = 0;
            double avgBits = 0;
            if (values.length > 0) {
                Trained t = train(toSortKeys(values), nBinsLog, dtypeSize);
                ansSizeLog = t.q().sizeLog();
                int[] weights = t.q().weights();
                binBits = (long) t.bins().size() * (ansSizeLog + dtypeSize + bitsToEncodeOffsetBits(dtypeSize));
                double totalWeight = 1 << ansSizeLog;
                for (int i = 0; i < weights.length; i++) {
                    double ansBits = ansSizeLog - Math.log(weights[i]) / Math.log(2.0);
                    avgBits += (ansBits + t.bins().get(i).offsetBits()) * weights[i] / totalWeight;
                }
            }
            long metaBits = CLASSIC_MODE_MAX_BITS + DELTA_ENCODING_MAX_BITS + 4 + 15 + binBits;
            long pageMetaBits = (long) ansSizeLog * ANS_INTERLEAVING + (long) dtypeSize * order;
            long bodyBits = (long) Math.ceil(values.length * avgBits);
            return ceilBytes(metaBits) + ceilBytes(pageMetaBits) + ceilBytes(bodyBits);
        }

        private static long ceilBytes(long bits) {
            return (bits + 7) >>> 3;
        }

        // Port of train_infos: histogram, bin optimization, weight quantization.
        private static Trained train(long[] sortKeys, int nBinsLog, int dtypeSize) {
            int n = sortKeys.length;
            long maxKey = typeMask(dtypeSize) ^ Long.MIN_VALUE;
            List<PcoHistBin> hist = PcoHistogram.histogram(sortKeys.clone(), nBinsLog, maxKey);
            int nLogCeil = n <= 1 ? 0 : 64 - Long.numberOfLeadingZeros((long) n - 1);
            int maxSizeLog = Math.min(Math.min(nBinsLog + 2, 12), nLogCeil);
            List<PcoBinOptimizer.Bin> bins = PcoBinOptimizer.optimize(hist, maxSizeLog, dtypeSize);
            return new Trained(bins, quantize(bins, n, maxSizeLog));
        }

        // ── IntMult mode (mode=1, NoOp delta on both streams) ─────────────────

        // Rust trusts choose_base: once a base is chosen there is no Classic comparison.
        private static ChunkResult encodeIntMult(long[] latents, long base, int dtypeSize, Arena arena) {
            int n = latents.length;
            long[] mults = new long[n];
            long[] adjs = new long[n];
            for (int i = 0; i < n; i++) {
                mults[i] = Long.divideUnsigned(latents[i], base);
                adjs[i] = Long.remainderUnsigned(latents[i], base);
            }

            // Train bins for both streams independently.
            int nBinsLog = unoptimizedBinsLog(n);
            long[] multSortKeys = toSortKeys(mults);
            Trained mult = train(multSortKeys, nBinsLog, dtypeSize);
            List<PcoBinOptimizer.Bin> multBins = mult.bins();
            PcoWeightQuantizer.Result multQ = mult.q();
            long[] adjSortKeys = toSortKeys(adjs);
            // Rust trains secondary latents with fewer bins (LIMITED_UNOPTIMIZED_BINS_LOG)
            Trained adj = train(adjSortKeys, Math.min(nBinsLog, LIMITED_UNOPTIMIZED_BINS_LOG), dtypeSize);
            List<PcoBinOptimizer.Bin> adjBins = adj.bins();
            PcoWeightQuantizer.Result adjQ = adj.q();

            MemorySegment chunkMetaSeg = buildIntMultChunkMeta(dtypeSize, base, multBins, multQ, adjBins, adjQ, arena);
            int[] pageNs = pageNs(n);
            PageStream primary = new PageStream(mult, pageNs[0]);
            PageStream secondary = new PageStream(adj, pageNs[0]);
            LeBitWriter w = new LeBitWriter(pageNs[0] * 2 * (dtypeSize / 8) + 64);
            List<MemorySegment> pages = new ArrayList<>(pageNs.length);
            int pageStart = 0;
            for (int pageN : pageNs) {
                primary.prepare(multSortKeys, pageStart, pageN);
                secondary.prepare(adjSortKeys, pageStart, pageN);
                pages.add(buildIntMultPage(w, primary, secondary, arena));
                pageStart += pageN;
            }
            return new ChunkResult(chunkMetaSeg, pages, pageNs);
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
            LeBitWriter w, PageStream stream, int dtypeSize, long moment, boolean hasMoment, Arena arena) {
            w.reset();
            if (hasMoment) {
                w.writeBits(moment, dtypeSize);
            }
            stream.writeInitialStates(w);
            w.alignToByte();
            for (int batchStart = 0; batchStart < stream.n; batchStart += BATCH_N) {
                stream.writeBatch(w, batchStart, Math.min(BATCH_N, stream.n - batchStart));
            }
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        // ── IntMult page encoding ─────────────────────────────────────────────

        private static MemorySegment buildIntMultPage(
            LeBitWriter w, PageStream primary, PageStream secondary, Arena arena) {
            w.reset();
            // No moments: deltaOrder=0 on both streams. Primary states (4), then secondary states (4).
            primary.writeInitialStates(w);
            secondary.writeInitialStates(w);
            w.alignToByte();
            // Per-batch: primary ANS + primary offsets, then secondary ANS + secondary offsets.
            int n = primary.n;
            for (int batchStart = 0; batchStart < n; batchStart += BATCH_N) {
                int batchSize = Math.min(BATCH_N, n - batchStart);
                primary.writeBatch(w, batchStart, batchSize);
                secondary.writeBatch(w, batchStart, batchSize);
            }
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        // ── Page stream: one latent variable's bins, ANS encoder and per-page buffers ──

        // Reused for every page of a chunk: the buffers hold one page (at most maxPageN values) and
        // are overwritten by each prepare.
        private static final class PageStream {

            private final long[] binLowers;
            private final int[] binOffsetBits;
            private final int ansSizeLog;
            private final PcoAnsEncoder ansEncoder;
            private final int[] symbols;
            private final long[] offsets;
            private final int[] offsetWidths;
            private final long[] ansBits;
            private final int[] ansNumBits;
            private final int[] states = new int[ANS_INTERLEAVING];
            private int n;

            PageStream(Trained trained, int maxPageN) {
                List<PcoBinOptimizer.Bin> bins = trained.bins();
                binLowers = new long[bins.size()];
                binOffsetBits = new int[bins.size()];
                for (int i = 0; i < bins.size(); i++) {
                    binLowers[i] = bins.get(i).lowerSortKey();
                    binOffsetBits[i] = bins.get(i).offsetBits();
                }
                ansSizeLog = trained.q().sizeLog();
                ansEncoder = PcoAnsEncoder.build(ansSizeLog, trained.q().weights());
                symbols = new int[maxPageN];
                offsets = new long[maxPageN];
                offsetWidths = new int[maxPageN];
                ansBits = new long[maxPageN];
                ansNumBits = new int[maxPageN];
            }

            // Bins and offsets of sortKeys[from, from + count), then their ANS steps in reverse
            // from the default state, as Rust's dissect_page.
            void prepare(long[] sortKeys, int from, int count) {
                n = count;
                for (int i = 0; i < count; i++) {
                    long key = sortKeys[from + i];
                    int sym = findBin(key, binLowers);
                    symbols[i] = sym;
                    offsets[i] = key - binLowers[sym];
                    offsetWidths[i] = binOffsetBits[sym];
                }
                Arrays.fill(states, ansEncoder.defaultState());
                for (int i = count - 1; i >= 0; i--) {
                    int strm = i & (ANS_INTERLEAVING - 1);
                    PcoAnsEncoder.Step step = ansEncoder.encode(states[strm], symbols[i]);
                    ansBits[i] = step.bits();
                    ansNumBits[i] = step.numBits();
                    states[strm] = step.newState();
                }
            }

            void writeInitialStates(LeBitWriter w) {
                for (int state : states) {
                    w.writeBits(ansEncoder.toStateIdx(state), ansSizeLog);
                }
            }

            void writeBatch(LeBitWriter w, int batchStart, int batchSize) {
                int batchEnd = batchStart + batchSize;
                // ANS bits, then offset bits, both in forward order
                w.writeBits(ansBits, ansNumBits, batchStart, batchEnd);
                w.writeBits(offsets, offsetWidths, batchStart, batchEnd);
            }
        }

        // The last bin whose lower bound is <= sortKey. Branch-free: the key is random against the bins,
        // so a branchy search mispredicts about once per step.
        private static int findBin(long sortKey, long[] binLowers) {
            int base = 0;
            int len = binLowers.length;
            while (len > 1) {
                int half = len >>> 1;
                base = binLowers[base + half] <= sortKey ? base + half : base;
                len -= half;
            }
            return base;
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

        private static EncodeResult encodeEmpty() {
            byte[] header = {PCO_FORMAT_MAJOR, PCO_FORMAT_MINOR};
            ProtoPcoMetadata meta = new ProtoPcoMetadata(header, List.of());
            MemorySegment metaBuf = MemorySegment.ofArray(meta.encode());
            EncodeNode node = new EncodeNode(EncodingId.VORTEX_PCO, metaBuf, new EncodeNode[0], new int[0]);
            return new EncodeResult(node, List.of(), null, null);
        }

        private static MemorySegment buildMetadata(List<ProtoPcoChunkInfo> chunks) {
            byte[] header = {PCO_FORMAT_MAJOR, PCO_FORMAT_MINOR};
            ProtoPcoMetadata meta = new ProtoPcoMetadata(header, chunks);
            return MemorySegment.ofArray(meta.encode());
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
