package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoOnPairMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/// Write-only encoder for `vortex.onpair` (edition `core2026.08.1`).
///
/// Ports the `onpair` crate's trainer: starting from the 256 single-byte tokens, it scans a seeded
/// random sample of rows, splitting each with greedy longest-prefix match, and promotes an adjacent
/// token pair to a new token once the pair has occurred `threshold` times. The threshold adapts so
/// the dictionary (at most 4096 tokens of at most 16 bytes) fills over a 15% byte sample. The
/// dictionary is then sorted, and every row is encoded with greedy longest-prefix match over it.
///
/// Sorted + complete (all single bytes) + unique is what the Rust search kernels require: vortex-jni
/// tokenizes a pushed-down needle the same greedy way and compares codes, so the encoder's
/// segmentation must be exactly that. The dictionary itself need not match Rust's byte-for-byte
/// (its RNG is not portable), only satisfy those invariants.
///
/// Competes with FSST for string columns whenever the enabled editions include it, as Rust's
/// `OnPairScheme` does in its default compressor: [io.github.dfa1.vortex.writer.VortexWriter]
/// adds it as a cascade candidate under the default edition.
public final class OnPairEncodingEncoder implements EncodingEncoder {

    private static final Set<EncodingId> SELF = Set.of(EncodingId.VORTEX_ONPAIR);

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ONPAIR;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        OnPair c = Encoder.compress(data);
        Arena arena = ctx.arena();
        PType[] ptypes = c.childPTypes();
        long[][] values = c.childValues();
        List<EncodedBuffer> buffers = new ArrayList<>();
        buffers.add(EncodedBuffer.bytes(c.dictBytes(arena)));
        EncodeNode[] children = new EncodeNode[4];
        for (int i = 0; i < 4; i++) {
            MemorySegment seg = arena.allocate(Math.max(1, values[i].length * (long) ptypes[i].byteSize()));
            for (int j = 0; j < values[i].length; j++) {
                PTypeIO.set(seg, (long) j * ptypes[i].byteSize(), ptypes[i], values[i][j]);
            }
            buffers.add(EncodedBuffer.of(seg, ptypes[i]));
            children[i] = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, i + 1);
        }
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_ONPAIR, MemorySegment.ofArray(c.metadata()),
                children, new int[]{0});
        byte[][] stats = zoneMapStats(data);
        return new EncodeResult(root, buffers, stats != null ? stats[0] : null, stats != null ? stats[1] : null);
    }

    /// Leaves the four integer children open, so the cascade can bit-pack, frame-of-reference or
    /// constant-fold them, as Rust's compressor does.
    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        if (ctx.allowedCascading() == 0) {
            return CascadeStep.terminal(encode(dtype, data, ctx));
        }
        OnPair c = Encoder.compress(data);
        PType[] ptypes = c.childPTypes();
        long[][] values = c.childValues();
        List<ChildSlot> slots = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            slots.add(new ChildSlot(new DType.Primitive(ptypes[i], false),
                    PrimitiveArrays.fromLongsArray(values[i], ptypes[i], EncodingId.VORTEX_ONPAIR), i, SELF));
        }
        EncodeNode partialRoot = new EncodeNode(EncodingId.VORTEX_ONPAIR, MemorySegment.ofArray(c.metadata()),
                new EncodeNode[4], new int[]{0});
        byte[][] stats = zoneMapStats(data);
        return new CascadeStep(partialRoot, List.of(EncodedBuffer.bytes(c.dictBytes(ctx.arena()))), slots,
                stats != null ? stats[0] : null, stats != null ? stats[1] : null, true);
    }
    private static byte[][] zoneMapStats(Object data) {
        return data instanceof String[] strings ? VarBinEncodingEncoder.minMaxStats(strings) : null;
    }

    /// The compressed column: read-padded dictionary bytes plus the four integer children in slot
    /// order (`dict_offsets`, `codes`, `codes_offsets`, `uncompressed_lengths`).
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    record OnPair(byte[] dict, long[] dictOffsets, long[] codes, long[] codesOffsets, long[] lengths) {

        PType[] childPTypes() {
            long maxLength = 0;
            for (long l : lengths) {
                maxLength = Math.max(maxLength, l);
            }
            return new PType[]{
                PType.narrowestUnsigned(dictOffsets[dictOffsets.length - 1]),
                PType.narrowestUnsigned((long) dictOffsets.length - 2),
                PType.narrowestUnsigned(codesOffsets[codesOffsets.length - 1]),
                PType.narrowestUnsigned(maxLength)};
        }

        long[][] childValues() {
            return new long[][]{dictOffsets, codes, codesOffsets, lengths};
        }

        MemorySegment dictBytes(Arena arena) {
            MemorySegment seg = arena.allocate(dict.length, 8);
            MemorySegment.copy(dict, 0, seg, ValueLayout.JAVA_BYTE, 0, dict.length);
            return seg;
        }

        byte[] metadata() {
            PType[] p = childPTypes();
            return new ProtoOnPairMetadata(wire(p[3]), dictOffsets.length - 1, codes.length,
                    wire(p[0]), wire(p[1]), wire(p[2])).encode();
        }

        private static ProtoPType wire(PType ptype) {
            return ProtoPType.fromValue(ptype.ordinal());
        }
    }

    private static final class Encoder {

        /// Maximum token length; also the read padding the Rust decoder requires past the last token.
        static final int MAX_TOKEN_SIZE = 16;
        /// `onpair::DEFAULT_CONFIG`: 2^12 tokens, 15% byte sample, seed 42.
        static final int DICT_CAPACITY = 1 << 12;
        static final double SAMPLE_FRACTION = 0.15;
        static final long SEED = 42;

        static OnPair compress(Object data) {
            VarBinBytes.Rows rows = VarBinBytes.toContiguous(data);
            if (rows == null) {
                throw new UnsupportedOperationException("vortex.onpair: chunk exceeds 2 GB of row bytes");
            }
            Trie trained = train(rows);
            byte[][] tokens = trained.sortedTokens();
            Trie dict = new Trie();
            long[] dictOffsets = new long[tokens.length + 1];
            byte[] dictBytes = new byte[0];
            int size = 0;
            for (int t = 0; t < tokens.length; t++) {
                dict.insert(tokens[t], 0, tokens[t].length);
                if (size + tokens[t].length > dictBytes.length) {
                    dictBytes = Arrays.copyOf(dictBytes, Math.max(2 * dictBytes.length, size + tokens[t].length));
                }
                System.arraycopy(tokens[t], 0, dictBytes, size, tokens[t].length);
                size += tokens[t].length;
                dictOffsets[t + 1] = size;
            }
            int lastStart = (int) dictOffsets[tokens.length - 1];
            byte[] padded = Arrays.copyOf(dictBytes, Math.max(size, lastStart + MAX_TOKEN_SIZE));

            int n = rows.count();
            int[] offsets = rows.offsets();
            byte[] bytes = rows.bytes();
            long[] codes = new long[offsets[n] - offsets[0]];
            long[] codesOffsets = new long[n + 1];
            long[] lengths = new long[n];
            int numCodes = 0;
            for (int i = 0; i < n; i++) {
                int pos = offsets[i];
                int end = offsets[i + 1];
                lengths[i] = (long) end - pos;
                while (pos < end) {
                    long match = dict.longestMatch(bytes, pos, end);
                    codes[numCodes++] = Trie.token(match);
                    pos += Trie.length(match);
                }
                codesOffsets[i + 1] = numCodes;
            }
            return new OnPair(padded, dictOffsets, Arrays.copyOf(codes, numCodes), codesOffsets, lengths);
        }

        /// `discover_tokens` over a seeded random row order, until the byte budget or the
        /// dictionary capacity runs out.
        static Trie train(VarBinBytes.Rows rows) {
            Trie trie = new Trie();
            byte[] single = new byte[1];
            for (int b = 0; b < 256; b++) {
                single[0] = (byte) b;
                trie.insert(single, 0, 1);
            }
            int n = rows.count();
            int[] offsets = rows.offsets();
            byte[] bytes = rows.bytes();
            int[] order = new int[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            SplittableRandom random = new SplittableRandom(SEED);
            for (int i = n - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
            long totalBytes = (long) offsets[n] - offsets[0];
            Threshold threshold = new Threshold((long) DICT_CAPACITY - 256, totalBytes);
            LongIntMap pairs = new LongIntMap(1024);
            for (int r : order) {
                int start = offsets[r];
                int end = offsets[r + 1];
                if (start == end) {
                    continue;
                }
                long prev = trie.longestMatch(bytes, start, end);
                int pos = start + Trie.length(prev);
                if (threshold.scanned(Trie.length(prev))) {
                    break;
                }
                while (pos < end) {
                    long curr = trie.longestMatch(bytes, pos, end);
                    if (threshold.scanned(Trie.length(curr))) {
                        return trie;
                    }
                    int prevLen = Trie.length(prev);
                    int pairLen = prevLen + Trie.length(curr);
                    if (pairLen <= MAX_TOKEN_SIZE) {
                        long key = ((long) Trie.token(prev) << 16) | Trie.token(curr);
                        if (pairs.increment(key) >= threshold.value) {
                            int id = trie.insert(bytes, pos - prevLen, pos + Trie.length(curr));
                            if (trie.size() == DICT_CAPACITY) {
                                return trie;
                            }
                            threshold.entryCreated();
                            pairs.put(key, 0);
                            prev = Trie.match(id, pairLen);
                            pos += Trie.length(curr);
                            continue;
                        }
                    }
                    prev = curr;
                    pos += Trie.length(curr);
                }
            }
            return trie;
        }
    }

    /// `DynamicThresholdController`: raises the merge threshold when tokens are being created
    /// faster than the remaining capacity over the remaining byte budget, lowers it when slower.
    private static final class Threshold {
        final long capacity;
        final long budget;
        final long checkInterval;
        int value = 2;
        long created;
        long scanned;
        long createdAtCheck;
        long scannedAtCheck;
        long nextCheck;

        Threshold(long capacity, long totalBytes) {
            this.capacity = capacity;
            this.budget = (long) (totalBytes * Encoder.SAMPLE_FRACTION);
            this.checkInterval = Math.max(capacity / 128, 64);
            this.nextCheck = checkInterval;
        }

        /// @return `true` once the scan budget is exhausted
        boolean scanned(int n) {
            scanned += n;
            return scanned > budget;
        }

        void entryCreated() {
            created++;
            if (created < nextCheck) {
                return;
            }
            long deltaBytes = scanned - scannedAtCheck;
            double recentRate = deltaBytes > 0 ? (double) (created - createdAtCheck) / deltaBytes : 1e9;
            double targetRate = (double) Math.max(capacity - created, 1) / Math.max(budget - scanned, 1);
            double ratio = recentRate / targetRate;
            if (ratio > 2.0 && value < 255) {
                value++;
            } else if (ratio < 0.5 && value > 2) {
                value--;
            }
            createdAtCheck = created;
            scannedAtCheck = scanned;
            nextCheck = created + checkInterval;
        }
    }

    /// Byte trie over the dictionary tokens for greedy longest-prefix match. Edges live in one
    /// [LongIntMap] keyed by `(node << 8) | byte`; node 0 is the root.
    private static final class Trie {
        private final LongIntMap edges = new LongIntMap(8192);
        private int[] nodeToken = {-1};
        private int nodes = 1;
        private final List<byte[]> tokens = new ArrayList<>();

        /// Adds `src[from..to)` as the next token id, or returns the existing id if it already is
        /// one: a pair right after a merge can spell an existing token, and duplicates would break
        /// the dictionary's uniqueness invariant.
        int insert(byte[] src, int from, int to) {
            int node = 0;
            for (int i = from; i < to; i++) {
                long key = ((long) node << 8) | (src[i] & 0xFF);
                int child = edges.get(key);
                if (child < 0) {
                    child = nodes++;
                    if (child == nodeToken.length) {
                        nodeToken = Arrays.copyOf(nodeToken, 2 * child);
                        Arrays.fill(nodeToken, child, nodeToken.length, -1);
                    }
                    edges.put(key, child);
                }
                node = child;
            }
            if (nodeToken[node] < 0) {
                nodeToken[node] = tokens.size();
                tokens.add(Arrays.copyOfRange(src, from, to));
            }
            return nodeToken[node];
        }

        int size() {
            return tokens.size();
        }

        /// Longest token that prefixes `src[from..to)`, packed as [#match(int, int)]. Every single
        /// byte is a token, so the match is at least one byte long.
        long longestMatch(byte[] src, int from, int to) {
            int limit = Math.min(to, from + Encoder.MAX_TOKEN_SIZE);
            int node = 0;
            long best = 0;
            for (int i = from; i < limit; i++) {
                node = edges.get(((long) node << 8) | (src[i] & 0xFF));
                if (node < 0) {
                    break;
                }
                if (nodeToken[node] >= 0) {
                    best = match(nodeToken[node], i - from + 1);
                }
            }
            return best;
        }

        /// The tokens in ascending unsigned-lexicographic order.
        byte[][] sortedTokens() {
            byte[][] sorted = tokens.toArray(new byte[0][]);
            Arrays.sort(sorted, Arrays::compareUnsigned);
            return sorted;
        }

        static long match(int token, int length) {
            return ((long) token << 8) | length;
        }

        static int token(long match) {
            return (int) (match >>> 8);
        }

        static int length(long match) {
            return (int) (match & 0xFF);
        }
    }
}
