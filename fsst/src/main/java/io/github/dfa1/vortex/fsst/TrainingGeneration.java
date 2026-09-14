package io.github.dfa1.vortex.fsst;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.PriorityQueue;

/// One generation of FSST bottom-up training (the FSST paper's Algorithm 3): compress-count the
/// sample with the current symbol table, then build the next table from the top-gain candidates.
///
/// Compress-count parses each sample chunk with the current [Compressor] using longest-match-first
/// greedy parsing, tallying how often each matched symbol occurs (`count1`) and how often each pair
/// of adjacent matches occurs (`count2`). Make-table then proposes a candidate for every counted
/// symbol (its own bytes) and every counted adjacent pair (their concatenation, truncated to 8
/// bytes), ranks them by gain, and keeps the top 255.
///
/// Beyond the bare paper this ports the Rust reference's four refinements (issue #287), each guarded
/// and documented at its site below: per-generation min-count pruning, the 8x single-byte gain
/// boost, and — on the final generation only — a cost-based prune pass. The growing sample fraction
/// (the fourth refinement) is applied by the caller via [Sample#chunkCountForGeneration(int)].
///
/// Counting is keyed by *code*, not by packed bytes (issue #393 #395): every match [Matcher] returns
/// already carries a code, and every non-match (escape) reduces to one of 256 literal byte values —
/// together a fixed, dense `[0, CODE_SPACE)` space matching the Rust reference's own
/// (`spiraldb/fsst/src/builder.rs`'s `Counter`, `FSST_CODE_MASK`-sized `count1`/`count2`). [Counts]
/// tallies both into flat arrays with no hashing, no boxing, and no per-generation reallocation — a
/// [BitSet] per array tracks which slots this generation actually touched, so clearing between
/// generations only resets the (small) touched-bit tracking, not the (large) count arrays
/// themselves. A single [Counts] instance is meant to be reused across every generation of one
/// [CompressorBuilder#train(byte[][])] call via [#run(Compressor, Sample, int, int, boolean, Counts)].
final class TrainingGeneration {

    /// Maximum number of real symbols kept per generation (codes `0..254`; `0xFF` is the escape).
    private static final int MAX_SYMBOLS = 255;

    /// Maximum symbol length in bytes, bounded by what fits in one `long`.
    private static final int MAX_SYMBOL_LENGTH = 8;

    /// First code in the escape pseudo-code range: real trained-symbol codes are always
    /// `< ESCAPE_BASE` (bounded by [#MAX_SYMBOLS], since a [Matcher] built from `current` can never
    /// return a code past its own table size), so `ESCAPE_BASE + byteValue` (`byteValue` in `0..255`)
    /// gives every possible escaped literal byte its own code with no possibility of collision.
    private static final int ESCAPE_BASE = MAX_SYMBOLS;

    /// Total dense code space: [#MAX_SYMBOLS] real-symbol slots plus 256 escape pseudo-codes, one
    /// per literal byte value. Fixed regardless of how many symbols are actually trained so far in
    /// any given generation, which is what lets [Counts]'s arrays stay a constant size and be reused
    /// across generations instead of resized as the table grows.
    private static final int CODE_SPACE = ESCAPE_BASE + 256;

    /// Single-byte gain multiplier. WHY: multiplying every length-1 candidate's gain by 8 before
    /// ranking deliberately over-values frequent single bytes relative to their raw `count*length`.
    /// A single byte in the table replaces a 2-byte escape on every occurrence, so keeping frequent
    /// bytes suppresses escapes; without the boost a marginally-higher-gain multi-byte candidate can
    /// crowd a genuinely more valuable single byte out of the 255 slots. The old encoder used plain
    /// gain and had exactly this gap.
    private static final int SINGLE_BYTE_GAIN_BOOST = 8;

    private TrainingGeneration() {
    }

    /// Runs one training generation with a fresh [Counts], for callers that do not need to reuse
    /// counting storage across generations (tests; single-generation callers).
    ///
    /// @param current the current generation's compressor (an empty-table compressor for generation
    ///                0, so every position escapes and bootstraps single-byte candidates)
    /// @param sample the fixed training sample
    /// @param chunkLimit the number of leading sample chunks this generation replays (its growing
    ///                   fraction), from [Sample#chunkCountForGeneration(int)]
    /// @param sampleFractionNumerator this generation's sample-fraction numerator over
    ///                                [Sample#SAMPLE_FRACTION_DENOMINATOR], used for the min-count
    ///                                floor
    /// @param finalGeneration whether this is the last generation, which relaxes the min-count floor
    ///                        to 1 and enables the cost-based final prune
    /// @return the next generation's symbols, gain-descending, at most [#MAX_SYMBOLS] of them
    static List<Symbol> run(Compressor current, Sample sample, int chunkLimit,
                            int sampleFractionNumerator, boolean finalGeneration) {
        return run(current, sample, chunkLimit, sampleFractionNumerator, finalGeneration, new Counts());
    }

    /// Runs one training generation and returns the next generation's symbols in gain-descending
    /// order (code = list index), ready to build the next [Compressor] / [Matcher].
    ///
    /// @param current the current generation's compressor (an empty-table compressor for generation
    ///                0, so every position escapes and bootstraps single-byte candidates)
    /// @param sample the fixed training sample
    /// @param chunkLimit the number of leading sample chunks this generation replays (its growing
    ///                   fraction), from [Sample#chunkCountForGeneration(int)]
    /// @param sampleFractionNumerator this generation's sample-fraction numerator over
    ///                                [Sample#SAMPLE_FRACTION_DENOMINATOR], used for the min-count
    ///                                floor
    /// @param finalGeneration whether this is the last generation, which relaxes the min-count floor
    ///                        to 1 and enables the cost-based final prune
    /// @param counts reusable counting storage, cleared at the start of this call; passing the same
    ///               instance across every generation of one `train()` call avoids reallocating its
    ///               backing arrays five times over
    /// @return the next generation's symbols, gain-descending, at most [#MAX_SYMBOLS] of them
    static List<Symbol> run(Compressor current, Sample sample, int chunkLimit,
                            int sampleFractionNumerator, boolean finalGeneration, Counts counts) {
        compressCount(current, sample, chunkLimit, counts);
        return makeTable(current, counts, sampleFractionNumerator, finalGeneration);
    }

    /// Compress-counts the first `chunkLimit` chunks of `sample` into `counts` (cleared first):
    /// greedily parses each chunk with `current`, tallying single-match counts and adjacent-pair
    /// counts, both keyed by code (generation 0's empty table escapes everything, seeding the escape
    /// pseudo-codes for single bytes).
    ///
    /// Each position loads its 8-byte input word exactly once. While 8 real bytes remain, the word
    /// loads with one intrinsified `VarHandle` read (the same trick [Compressor#compress(byte[],
    /// int, int, byte[], long)] uses); only the tail of each chunk falls back to the byte-by-byte,
    /// zero-padding [Compressor#loadWord(byte[], int, int)].
    private static void compressCount(Compressor current, Sample sample, int chunkLimit, Counts counts) {
        counts.clear();
        byte[] bytes = sample.bytes();
        for (int c = 0; c < chunkLimit; c++) {
            int start = sample.chunkStart(c);
            int end = sample.chunkEnd(c);
            int previousCode = -1; // No previous match within this chunk yet.
            int pos = start;
            int fastEnd = end - 8;
            while (pos < end) {
                long word = pos <= fastEnd
                        ? (long) Compressor.LONG_LE_BYTES.get(bytes, pos)
                        : Compressor.loadWord(bytes, pos, end);
                int codeAndLength = codeAndLengthAt(current, word, pos, end);
                int code = Matcher.codeOf(codeAndLength);
                counts.bumpSingle(code);
                if (previousCode >= 0) {
                    counts.bumpPair(previousCode, code);
                }
                previousCode = code;
                pos += Matcher.lengthOf(codeAndLength);
            }
        }
    }

    /// Returns the code and length of the current table's longest match against the already-loaded
    /// `word` at `pos`, packed as `code << 8 | length` (the same shape [Matcher#longestMatch(long)]
    /// itself returns). Falls back to the escape pseudo-code for the literal byte at `pos` (length 1)
    /// when nothing matches.
    ///
    /// An over-long match is rejected the same way [Compressor#compress(byte[], int, int, byte[],
    /// long)] rejects it: `word` zero-pads past `end` when loaded via [Compressor#loadWord(byte[],
    /// int, int)], so a symbol with trailing zero bytes can spuriously match the padding beyond the
    /// chunk. Counting such a match would tally a candidate spanning bytes that are not really in
    /// the sample, and could advance `pos` past `end`.
    private static int codeAndLengthAt(Compressor current, long word, int pos, int end) {
        int packedMatch = current.matcher().longestMatch(word);
        int length = Matcher.lengthOf(packedMatch);
        if (length > 0 && pos + length <= end) {
            return packedMatch;
        }
        return (ESCAPE_BASE + (int) (word & 0xFF)) << 8 | 1;
    }

    /// Builds the next table from the counted candidates: proposes each single symbol and each
    /// adjacent-pair concatenation, prunes by min-count, boosts single-byte gain, ranks, applies the
    /// final cost prune on the last generation, and keeps the top [#MAX_SYMBOLS] by gain.
    ///
    /// @param current the compressor `counts` was tallied against — real (non-escape) codes resolve
    ///                 to their bytes/length through it
    private static List<Symbol> makeTable(Compressor current, Counts counts,
                                          int sampleFractionNumerator, boolean finalGeneration) {
        // Refinement 2 — per-generation min-count pruning. A candidate seen fewer times than this
        // floor is noise at this generation's sample coverage and is dropped before its gain is even
        // computed. The floor scales with the sample fraction so early, low-coverage generations
        // prune more aggressively; the final generation relaxes it to 1 so rare-but-real candidates
        // survive the last, authoritative ranking pass.
        int minCount = finalGeneration
                ? 1
                : Math.max(1, 5 * sampleFractionNumerator / Sample.SAMPLE_FRACTION_DENOMINATOR);

        List<Candidate> candidates = new ArrayList<>();
        counts.forEachSingle((code, count) -> {
            if (count >= minCount) {
                int length = candidateLength(current, code);
                long packed = candidateBytes(current, code);
                candidates.add(new Candidate(packed, length, gainOf(count, length)));
            }
        });
        counts.forEachPair((firstCode, secondCode, count) -> {
            if (count < minCount) {
                return;
            }
            int firstLength = candidateLength(current, firstCode);
            long firstPacked = candidateBytes(current, firstCode);
            int secondLength = candidateLength(current, secondCode);
            long secondPacked = candidateBytes(current, secondCode);
            int combinedLength = Math.min(firstLength + secondLength, MAX_SYMBOL_LENGTH);
            long combined = concatenate(firstPacked, firstLength, secondPacked);
            long packed = combined & lengthMask(combinedLength);
            candidates.add(new Candidate(packed, combinedLength, gainOf(count, combinedLength)));
        });

        return selectTop(candidates, finalGeneration);
    }

    /// Resolves a code's packed bytes: `current`'s trained symbol table for a real code, or the
    /// literal byte value itself for an escape pseudo-code.
    private static long candidateBytes(Compressor current, int code) {
        return code < ESCAPE_BASE ? current.packedSymbol(code) : (long) (code - ESCAPE_BASE);
    }

    /// Resolves a code's byte length: `current`'s trained symbol table for a real code, or 1 for an
    /// escape pseudo-code (every escape is a single literal byte).
    private static int candidateLength(Compressor current, int code) {
        return code < ESCAPE_BASE ? current.symbolLength(code) : 1;
    }

    /// Ranks candidates by gain-descending (length-descending on ties) and keeps the top
    /// [#MAX_SYMBOLS], applying the final cost prune on the last generation. Uses a bounded min-heap
    /// so a large candidate set is not fully sorted — only the surviving 255 are.
    private static List<Symbol> selectTop(List<Candidate> candidates, boolean finalGeneration) {
        // Refinement 4 — final cost-based prune, applied BEFORE top-K selection. Only on the last
        // generation, a symbol earns its code slot only if its real (un-boosted) gain exceeds
        // length+1: it must save at least that many bytes over escaping every occurrence, otherwise
        // the slot is better left for a candidate that does. Pruning after the heap (as opposed to
        // before) would leave a disqualified survivor's slot empty even when a qualifying candidate
        // sat just below the top-255 cut, so the 255 slots would not all be filled with symbols that
        // earn them. Provisional earlier generations skip this so their symbols stay available to be
        // re-evaluated next pass.
        List<Candidate> eligible = candidates;
        if (finalGeneration) {
            eligible = new ArrayList<>(candidates.size());
            for (Candidate candidate : candidates) {
                if (realGain(candidate) > candidate.length() + 1L) {
                    eligible.add(candidate);
                }
            }
        }

        // A bounded min-heap of size MAX_SYMBOLS: the weakest survivor sits at the head, so a new
        // candidate either loses to it (discarded) or evicts it. This is top-K in O(n log K) rather
        // than an O(n log n) full sort of every candidate.
        PriorityQueue<Candidate> heap = new PriorityQueue<>(TrainingGeneration::compareGain);
        for (Candidate candidate : eligible) {
            if (heap.size() < MAX_SYMBOLS) {
                heap.add(candidate);
            } else if (compareGain(candidate, heap.peek()) > 0) {
                heap.poll();
                heap.add(candidate);
            }
        }

        List<Candidate> survivors = new ArrayList<>(heap);
        // Restore gain-descending order (the heap only guarantees the head is weakest).
        survivors.sort((a, b) -> compareGain(b, a));

        List<Symbol> result = new ArrayList<>(survivors.size());
        for (Candidate candidate : survivors) {
            result.add(new Symbol(candidate.packed(), candidate.length()));
        }
        return result;
    }

    /// Gain used for ranking: `count * length`, with the single-byte boost applied to length-1
    /// candidates (refinement 3).
    private static long gainOf(long count, int length) {
        long gain = count * length;
        return length == 1 ? gain * SINGLE_BYTE_GAIN_BOOST : gain;
    }

    /// Real (un-boosted) gain of a candidate, dividing the length-1 boost back out — used only by
    /// the final cost prune, which must judge a symbol on its true byte savings, not its boosted
    /// ranking gain.
    private static long realGain(Candidate candidate) {
        return candidate.length() == 1
                ? candidate.gain() / SINGLE_BYTE_GAIN_BOOST
                : candidate.gain();
    }

    private static int compareGain(Candidate a, Candidate b) {
        int byGain = Long.compare(a.gain(), b.gain());
        if (byGain != 0) {
            return byGain;
        }
        return Integer.compare(a.length(), b.length());
    }

    /// Concatenates two packed symbols LSB-first: the first symbol's bytes stay in the low bytes and
    /// the second symbol's bytes are shifted up by the first symbol's byte length, so the combined
    /// word reads the first symbol's bytes then the second's. A shift of 64 bits (first already 8
    /// bytes long) drops the second symbol entirely, which is correct — the result is then truncated
    /// to 8 bytes anyway.
    private static long concatenate(long first, int firstLength, long second) {
        if (firstLength >= MAX_SYMBOL_LENGTH) {
            return first;
        }
        return first | (second << (firstLength * 8));
    }

    private static long lengthMask(int length) {
        return length >= MAX_SYMBOL_LENGTH ? ~0L : (1L << (length * 8)) - 1;
    }

    /// A ranked candidate symbol: packed bytes, length, and ranking gain (boosted for length 1).
    private record Candidate(long packed, int length, long gain) {
    }

    /// Tallies of single-match and adjacent-pair counts during compress-count, keyed by *code* — a
    /// dense `[0, CODE_SPACE)` space, not packed bytes — matching the Rust reference's own
    /// `Counter` (`spiraldb/fsst/src/builder.rs`, issue #393 #395). `counts1`/`counts2` are flat
    /// arrays sized once and reused across every generation of a `train()` call: a code's identity
    /// alone (not its byte content) is the key, so there is no hashing, no boxing, and — since the
    /// code space is fixed regardless of how many symbols are actually trained in any one
    /// generation — no resizing either.
    ///
    /// Between generations only [#clear()] runs, which resets the touched-bit tracking, not the
    /// (much larger) count arrays: [#bumpSingle(int)]/[#bumpPair(int, int)] read a slot's prior value
    /// only when its touched bit is set, so a stale value left over from an earlier generation in an
    /// untouched slot is never read.
    static final class Counts {

        private final long[] counts1 = new long[CODE_SPACE];
        private final long[] counts2 = new long[CODE_SPACE * CODE_SPACE];
        private final BitSet singleTouched = new BitSet(CODE_SPACE);
        private final BitSet pairTouched = new BitSet(CODE_SPACE * CODE_SPACE);

        /// Resets counting for a new generation. Only the touched-bit tracking is cleared — cheap,
        /// proportional to how much was actually touched last generation — not the count arrays
        /// themselves.
        void clear() {
            singleTouched.clear();
            pairTouched.clear();
        }

        void bumpSingle(int code) {
            long base = singleTouched.get(code) ? counts1[code] : 0L;
            counts1[code] = base + 1;
            singleTouched.set(code);
        }

        void bumpPair(int firstCode, int secondCode) {
            int idx = firstCode * CODE_SPACE + secondCode;
            long base = pairTouched.get(idx) ? counts2[idx] : 0L;
            counts2[idx] = base + 1;
            pairTouched.set(idx);
        }

        void forEachSingle(SingleConsumer consumer) {
            for (int code = singleTouched.nextSetBit(0); code >= 0; code = singleTouched.nextSetBit(code + 1)) {
                consumer.accept(code, counts1[code]);
            }
        }

        void forEachPair(PairConsumer consumer) {
            for (int idx = pairTouched.nextSetBit(0); idx >= 0; idx = pairTouched.nextSetBit(idx + 1)) {
                consumer.accept(idx / CODE_SPACE, idx % CODE_SPACE, counts2[idx]);
            }
        }
    }

    @FunctionalInterface
    private interface SingleConsumer {
        void accept(int code, long count);
    }

    @FunctionalInterface
    private interface PairConsumer {
        void accept(int firstCode, int secondCode, long count);
    }
}
