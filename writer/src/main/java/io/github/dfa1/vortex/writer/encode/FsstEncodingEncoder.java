package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoFSSTMetadata;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Write-only encoder for `vortex.fsst`.
///
/// Builds a variable-length (1-8 byte) symbol table with iterative refinement, following the
/// FSST paper (Boncz/Neumann/Winter, "FSST: Fast Random Access String Compression"). Each
/// training pass compresses a bounded sample of the input with the current table using
/// longest-match-first greedy parsing, counts the byte savings of extending each matched symbol
/// by one more byte, then rebuilds the table from the top candidates. The symbol table converges
/// after a handful of passes, after which the whole input is compressed with the final table.
///
/// The wire format packs each symbol's bytes LSB-first into a `long` (first byte in the low byte)
/// alongside a per-symbol length byte, and reserves code `0xFF` as the single-literal-byte escape.
/// Real symbol codes are therefore `0..254` (max 255 symbols), each 1-8 bytes long.
public final class FsstEncodingEncoder implements EncodingEncoder {

    /// Escape opcode: emitted as `0xFF` followed by one literal byte.
    private static final int ESCAPE = 0xFF;

    /// Maximum number of real symbols (codes `0..254`; `0xFF` is the escape).
    private static final int MAX_SYMBOLS = 255;

    /// Maximum symbol length in bytes, bounded by what fits in one `long`.
    private static final int MAX_SYMBOL_LENGTH = 8;

    /// Number of training passes. The FSST paper reports the table stabilizing after roughly
    /// five iterations; each pass can only lengthen symbols by one byte, so five passes suffice
    /// to grow symbols up to length ~6 from an empty start, which captures nearly all of the gain
    /// on the string workloads seen here. More passes cost linear time for negligible benefit.
    private static final int TRAINING_ITERATIONS = 5;

    /// Cap on the number of input strings sampled per training pass. Training is O(sample bytes)
    /// per pass, so very large inputs are sampled rather than fully scanned; the final compression
    /// pass still runs over every string. 25k strings is a large-enough sample to learn a stable
    /// table while keeping training cost bounded.
    private static final int TRAINING_SAMPLE_STRINGS = 25_000;

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_FSST;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        String[] strings = (String[]) data;
        int n = strings.length;

        byte[][] byteArrays = new byte[n][];
        for (int i = 0; i < n; i++) {
            byteArrays[i] = strings[i].getBytes(StandardCharsets.UTF_8);
        }

        SymbolTable table = trainSymbolTable(byteArrays);

        byte[][] compressed = new byte[n][];
        for (int i = 0; i < n; i++) {
            compressed[i] = table.compress(byteArrays[i]);
        }

        Arena arena = ctx.arena();
        int numSymbols = table.size();

        MemorySegment symBuf = arena.allocate(Math.max(numSymbols * 8L, 1), 8);
        for (int i = 0; i < numSymbols; i++) {
            symBuf.setAtIndex(VortexFormat.LE_LONG, i, table.packedBytes(i));
        }

        MemorySegment symLenBuf = arena.allocate(Math.max(numSymbols, 1));
        for (int i = 0; i < numSymbols; i++) {
            symLenBuf.set(ValueLayout.JAVA_BYTE, i, (byte) table.length(i));
        }

        int totalCompressed = 0;
        for (byte[] c : compressed) {
            totalCompressed += c.length;
        }
        MemorySegment compBuf = arena.allocate(Math.max(totalCompressed, 1));
        long pos = 0;
        for (byte[] c : compressed) {
            MemorySegment.copy(MemorySegment.ofArray(c), 0, compBuf, pos, c.length);
            pos += c.length;
        }

        MemorySegment uncompLenBuf = arena.allocate(Math.max(n * 4L, 1), 4);
        for (int i = 0; i < n; i++) {
            uncompLenBuf.setAtIndex(VortexFormat.LE_INT, i, byteArrays[i].length);
        }

        MemorySegment codesOffBuf = arena.allocate((long) (n + 1) * 4, 4);
        long off = 0;
        codesOffBuf.setAtIndex(VortexFormat.LE_INT, 0, 0);
        for (int i = 0; i < n; i++) {
            off += compressed[i].length;
            codesOffBuf.setAtIndex(VortexFormat.LE_INT, (long) i + 1, (int) off);
        }

        byte[] metaBytes = new ProtoFSSTMetadata(
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I32.ordinal()),
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I32.ordinal())
        ).encode();

        EncodeNode uncompLensNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 3);
        EncodeNode codesOffNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 4);
        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_FSST,
                MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{uncompLensNode, codesOffNode},
                new int[]{0, 1, 2});

        return new EncodeResult(root,
                List.of(symBuf, symLenBuf, compBuf, uncompLenBuf, codesOffBuf),
                null, null);
    }

    /// Trains a symbol table by iteratively refining candidate symbols against a bounded sample.
    ///
    /// @param byteArrays the raw byte content of every input string
    /// @return the final symbol table (0-255 symbols, each 1-8 bytes)
    private static SymbolTable trainSymbolTable(byte[][] byteArrays) {
        int sampleSize = Math.min(byteArrays.length, TRAINING_SAMPLE_STRINGS);
        SymbolTable table = SymbolTable.empty();
        for (int iteration = 0; iteration < TRAINING_ITERATIONS; iteration++) {
            Map<SymbolCandidate, Long> counts = new HashMap<>();
            for (int s = 0; s < sampleSize; s++) {
                table.countCandidates(byteArrays[s], counts);
            }
            if (counts.isEmpty()) {
                break;
            }
            table = SymbolTable.fromRankedCandidates(counts);
        }
        return table;
    }

    /// A candidate symbol: up to 8 bytes packed LSB-first into a `long` (first byte in the low
    /// byte, matching the wire format) paired with an explicit length in `1..8`.
    ///
    /// @param packedBytes the symbol's bytes, LSB-first (byte `k` at bit position `k * 8`)
    /// @param length the symbol length in bytes, `1..8`
    private record SymbolCandidate(long packedBytes, int length) {
    }

    /// An immutable FSST symbol table plus the machinery to compress against it and to count
    /// extension candidates during training.
    private static final class SymbolTable {

        private final long[] packed;
        private final int[] lengths;
        private final int count;

        /// Index for O(1) exact-match lookup during greedy parsing and compression, keyed by
        /// `(packedBytes, length)`, mapping to the symbol code.
        private final Map<SymbolCandidate, Integer> codeByCandidate;

        private SymbolTable(long[] packed, int[] lengths, int count) {
            this.packed = packed;
            this.lengths = lengths;
            this.count = count;
            this.codeByCandidate = new HashMap<>(count * 2);
            for (int code = 0; code < count; code++) {
                codeByCandidate.put(new SymbolCandidate(packed[code], lengths[code]), code);
            }
        }

        static SymbolTable empty() {
            return new SymbolTable(new long[0], new int[0], 0);
        }

        /// Builds a table from ranked candidates, keeping the top `MAX_SYMBOLS` by estimated gain.
        ///
        /// Gain is `count * length`: the number of input bytes this symbol covers, since every
        /// symbol code — regardless of length — emits exactly one output byte. This is the FSST
        /// paper's "apparent gain" and it is what makes length-1 symbols competitive: a frequent
        /// single byte covers many input bytes at one output byte each and, crucially, replaces a
        /// 2-byte escape. Dropping a frequent single byte from the table forces escapes (2 output
        /// bytes per occurrence), which is the failure mode of a naive bigram-only table. Ties
        /// break toward longer symbols (strictly more valuable per code).
        static SymbolTable fromRankedCandidates(Map<SymbolCandidate, Long> counts) {
            record Ranked(SymbolCandidate candidate, long gain) {
            }
            Ranked[] ranked = new Ranked[counts.size()];
            int idx = 0;
            for (Map.Entry<SymbolCandidate, Long> e : counts.entrySet()) {
                long gain = e.getValue() * (long) e.getKey().length();
                ranked[idx++] = new Ranked(e.getKey(), gain);
            }
            Arrays.sort(ranked, (a, b) -> {
                int byGain = Long.compare(b.gain(), a.gain());
                if (byGain != 0) {
                    return byGain;
                }
                return Integer.compare(b.candidate().length(), a.candidate().length());
            });

            int keep = Math.min(ranked.length, MAX_SYMBOLS);
            SymbolCandidate[] kept = new SymbolCandidate[keep];
            for (int i = 0; i < keep; i++) {
                kept[i] = ranked[i].candidate();
            }

            // Wire order (Rust `FSSTData::validate_symbol_lengths`): multi-byte symbols
            // (length 2-8) first in non-decreasing length order, then all length-1 symbols.
            // Selection above picks candidates by gain; this final sort only reorders codes
            // to satisfy that wire contract, so ties within a length bucket are arbitrary.
            Arrays.sort(kept, (a, b) -> {
                boolean aSingle = a.length() == 1;
                boolean bSingle = b.length() == 1;
                if (aSingle != bSingle) {
                    return aSingle ? 1 : -1;
                }
                return Integer.compare(a.length(), b.length());
            });

            long[] packed = new long[keep];
            int[] lengths = new int[keep];
            for (int i = 0; i < keep; i++) {
                packed[i] = kept[i].packedBytes();
                lengths[i] = kept[i].length();
            }
            return new SymbolTable(packed, lengths, keep);
        }

        int size() {
            return count;
        }

        long packedBytes(int code) {
            return packed[code];
        }

        int length(int code) {
            return lengths[code];
        }

        /// Returns the code of the longest symbol matching `input` at `pos`, or -1 if
        /// none matches. Checks lengths `MAX_SYMBOL_LENGTH` down to 1.
        private int longestMatch(byte[] input, int pos) {
            int maxLen = Math.min(MAX_SYMBOL_LENGTH, input.length - pos);
            for (int len = maxLen; len >= 1; len--) {
                Integer code = codeByCandidate.get(new SymbolCandidate(pack(input, pos, len), len));
                if (code != null) {
                    return code;
                }
            }
            return -1;
        }

        /// Walks `input` with longest-match-first greedy parsing using the current table,
        /// accumulating training candidates into `counts`:
        /// - each single byte that starts a match (or an unmatched byte) as a length-1 candidate,
        ///   so the table can bootstrap from empty and keep its seed symbols;
        /// - the matched multi-byte symbol itself, so it survives the next rebuild;
        /// - the concatenation of the current match with the next match, capped at 8 bytes. This is
        ///   the FSST paper's core heuristic: pairing adjacent symbols lets symbols roughly double
        ///   in length each pass (not just grow by one byte), so length-8 symbols emerge in a few
        ///   passes rather than seven.
        void countCandidates(byte[] input, Map<SymbolCandidate, Long> counts) {
            int i = 0;
            while (i < input.length) {
                int code = longestMatch(input, i);
                int matchLen = code >= 0 ? lengths[code] : 1;

                // Always offer the length-1 symbol at this position (seeds/keeps single bytes).
                bump(counts, new SymbolCandidate(pack(input, i, 1), 1));

                if (matchLen > 1) {
                    // Reinforce the matched multi-byte symbol so it survives the next rebuild.
                    bump(counts, new SymbolCandidate(pack(input, i, matchLen), matchLen));
                }

                // Pair the current match with the following match to form a longer candidate.
                int next = i + matchLen;
                if (next < input.length && matchLen < MAX_SYMBOL_LENGTH) {
                    int nextCode = longestMatch(input, next);
                    int nextLen = nextCode >= 0 ? lengths[nextCode] : 1;
                    int pairLen = Math.min(matchLen + nextLen, MAX_SYMBOL_LENGTH);
                    bump(counts, new SymbolCandidate(pack(input, i, pairLen), pairLen));
                }

                i += matchLen;
            }
        }

        /// Compresses `input` with longest-match-first greedy parsing over the final table.
        /// Positions matching no symbol are emitted as an escape (`0xFF` + literal byte).
        byte[] compress(byte[] input) {
            byte[] out = new byte[input.length * 2];
            int outLen = 0;
            int i = 0;
            while (i < input.length) {
                int code = longestMatch(input, i);
                if (code >= 0) {
                    out[outLen++] = (byte) code;
                    i += lengths[code];
                } else {
                    out[outLen++] = (byte) ESCAPE;
                    out[outLen++] = input[i];
                    i++;
                }
            }
            return Arrays.copyOf(out, outLen);
        }

        private static void bump(Map<SymbolCandidate, Long> counts, SymbolCandidate candidate) {
            counts.merge(candidate, 1L, Long::sum);
        }

        /// Packs `len` bytes of `input` starting at `pos` LSB-first into a
        /// `long`: byte 0 in the low byte, matching the wire and decoder conventions.
        private static long pack(byte[] input, int pos, int len) {
            long value = 0;
            for (int k = 0; k < len; k++) {
                value |= Byte.toUnsignedLong(input[pos + k]) << (k * 8);
            }
            return value;
        }
    }
}
