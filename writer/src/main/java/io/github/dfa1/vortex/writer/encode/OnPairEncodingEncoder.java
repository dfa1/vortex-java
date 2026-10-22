package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.compute.LongIntMap;
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

    /// Creates the encoder.
    public OnPairEncodingEncoder() {
    }

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
        VarBinBytes.Rows rows = Encoder.rows(data);
        OnPair c = Encoder.compress(rows);
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
        MinMax stats = VarBinEncodingEncoder.textMinMax(dtype, data, rows);
        return new EncodeResult(root, buffers, stats != null ? stats.min() : null, stats != null ? stats.max() : null);
    }

    /// Leaves the four integer children open, so the cascade can bit-pack, frame-of-reference or
    /// constant-fold them, as Rust's compressor does.
    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        if (ctx.allowedCascading() == 0) {
            return CascadeStep.terminal(encode(dtype, data, ctx));
        }
        VarBinBytes.Rows rows = Encoder.rows(data);
        OnPair c = Encoder.compress(rows);
        PType[] ptypes = c.childPTypes();
        long[][] values = c.childValues();
        List<ChildSlot> slots = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            slots.add(new ChildSlot(new DType.Primitive(ptypes[i], false),
                    PrimitiveArrays.fromLongsArray(values[i], ptypes[i], EncodingId.VORTEX_ONPAIR), i, SELF));
        }
        EncodeNode partialRoot = new EncodeNode(EncodingId.VORTEX_ONPAIR, MemorySegment.ofArray(c.metadata()),
                new EncodeNode[4], new int[]{0});
        MinMax stats = VarBinEncodingEncoder.textMinMax(dtype, data, rows);
        return new CascadeStep(partialRoot, List.of(EncodedBuffer.bytes(c.dictBytes(ctx.arena()))), slots,
                stats != null ? stats.min() : null, stats != null ? stats.max() : null, true);
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

        static VarBinBytes.Rows rows(Object data) {
            VarBinBytes.Rows rows = VarBinBytes.toContiguous(data);
            if (rows == null) {
                throw new UnsupportedOperationException("vortex.onpair: chunk exceeds 2 GB of row bytes");
            }
            return rows;
        }

        static OnPair compress(VarBinBytes.Rows rows) {
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
                if (threshold.scan(Trie.length(prev))) {
                    break;
                }
                while (pos < end) {
                    long curr = trie.longestMatch(bytes, pos, end);
                    if (threshold.scan(Trie.length(curr))) {
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

        /// Counts `n` more bytes scanned.
        ///
        /// @return `true` once the scan budget is exhausted
        boolean scan(int n) {
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

    /// Byte trie over the dictionary tokens for greedy longest-prefix match; node 0 is the root.
    ///
    /// Every match steps through the root's child and nearly every one through a grandchild, so
    /// those edges are direct-indexed by the first byte and by the first two (`node + 1`, so `0`
    /// is absent); deeper edges live in an open-addressing table keyed by `(node << 8) | byte`.
    /// Hashing every step was 30% of a default-options CSV import, most of it cache misses.
    private static final class Trie {
        private final int[] depth1 = new int[1 << 8];
        private final int[] depth2 = new int[1 << 16];
        private final EdgeTable edges = new EdgeTable();
        private int[] nodeToken = {-1};
        private int nodes = 1;
        private final List<byte[]> tokens = new ArrayList<>();

        /// Adds `src[from..to)` as the next token id, or returns the existing id if it already is
        /// one: a pair right after a merge can spell an existing token, and duplicates would break
        /// the dictionary's uniqueness invariant.
        int insert(byte[] src, int from, int to) {
            int node = 0;
            for (int i = from; i < to; i++) {
                int child = child(node, src, from, i);
                if (child < 0) {
                    child = nodes++;
                    if (child == nodeToken.length) {
                        nodeToken = Arrays.copyOf(nodeToken, 2 * child);
                        Arrays.fill(nodeToken, child, nodeToken.length, -1);
                    }
                    setChild(node, src, from, i, child);
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
            long best = 0;
            // the first two steps peeled off the loop: direct-indexed, and no per-step branch on depth
            int first = src[from] & 0xFF;
            int node = depth1[first] - 1;
            if (node < 0) {
                return best;
            }
            if (nodeToken[node] >= 0) {
                best = match(nodeToken[node], 1);
            }
            if (from + 1 >= limit) {
                return best;
            }
            node = depth2[(first << 8) | (src[from + 1] & 0xFF)] - 1;
            if (node < 0) {
                return best;
            }
            if (nodeToken[node] >= 0) {
                best = match(nodeToken[node], 2);
            }
            for (int i = from + 2; i < limit; i++) {
                node = edges.get((node << 8) | (src[i] & 0xFF));
                if (node < 0) {
                    break;
                }
                if (nodeToken[node] >= 0) {
                    best = match(nodeToken[node], i - from + 1);
                }
            }
            return best;
        }

        /// The child of `node`, reached by `src[i]` from a path that started at `src[from]`, or -1.
        private int child(int node, byte[] src, int from, int i) {
            return switch (i - from) {
                case 0 -> depth1[src[i] & 0xFF] - 1;
                case 1 -> depth2[((src[from] & 0xFF) << 8) | (src[i] & 0xFF)] - 1;
                default -> edges.get((node << 8) | (src[i] & 0xFF));
            };
        }

        private void setChild(int node, byte[] src, int from, int i, int child) {
            switch (i - from) {
                case 0 -> depth1[src[i] & 0xFF] = child + 1;
                case 1 -> depth2[((src[from] & 0xFF) << 8) | (src[i] & 0xFF)] = child + 1;
                default -> edges.put((node << 8) | (src[i] & 0xFF), child);
            }
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

    /// The trie's deep edges: open addressing over one `long[]` of `(key << 32) | (child + 1)`, so a
    /// lookup reads one slot where [io.github.dfa1.vortex.core.compute.LongIntMap]'s parallel key
    /// and value arrays read two, and these lookups are cache misses. A key is `(node << 8) | byte`
    /// with node below 2^23 (at most 4096 tokens of 16 bytes), so it fits the upper half.
    private static final class EdgeTable {
        private static final long HASH_MULTIPLIER = 0x9E3779B97F4A7C15L;

        private long[] slots = new long[1 << 14];
        private int shift = Long.numberOfLeadingZeros(slots.length - 1L);
        private int size;

        /// The child for `key`, or -1.
        int get(int key) {
            int mask = slots.length - 1;
            int slot = slotOf(key);
            while (true) {
                long entry = slots[slot];
                if (entry == 0) {
                    return -1;
                }
                if ((int) (entry >>> 32) == key) {
                    return (int) entry - 1;
                }
                slot = (slot + 1) & mask;
            }
        }

        /// Adds the edge `key` -> `child`, which is not present yet.
        void put(int key, int child) {
            if (2 * (size + 1) > slots.length) {
                grow();
            }
            insert((long) key << 32 | (child + 1L));
            size++;
        }

        private void insert(long entry) {
            int mask = slots.length - 1;
            int slot = slotOf((int) (entry >>> 32));
            while (slots[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            slots[slot] = entry;
        }

        private void grow() {
            long[] old = slots;
            slots = new long[old.length * 4];
            shift = Long.numberOfLeadingZeros(slots.length - 1L);
            for (long entry : old) {
                if (entry != 0) {
                    insert(entry);
                }
            }
        }

        private int slotOf(int key) {
            return (int) ((key * HASH_MULTIPLIER) >>> shift);
        }
    }
}
