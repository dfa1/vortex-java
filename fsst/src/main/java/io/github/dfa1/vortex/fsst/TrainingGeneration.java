package io.github.dfa1.vortex.fsst;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
final class TrainingGeneration {

    /// Maximum number of real symbols kept per generation (codes `0..254`; `0xFF` is the escape).
    private static final int MAX_SYMBOLS = 255;

    /// Maximum symbol length in bytes, bounded by what fits in one `long`.
    private static final int MAX_SYMBOL_LENGTH = 8;

    /// Single-byte gain multiplier. WHY: multiplying every length-1 candidate's gain by 8 before
    /// ranking deliberately over-values frequent single bytes relative to their raw `count*length`.
    /// A single byte in the table replaces a 2-byte escape on every occurrence, so keeping frequent
    /// bytes suppresses escapes; without the boost a marginally-higher-gain multi-byte candidate can
    /// crowd a genuinely more valuable single byte out of the 255 slots. The old encoder used plain
    /// gain and had exactly this gap.
    private static final int SINGLE_BYTE_GAIN_BOOST = 8;

    private TrainingGeneration() {
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
    /// @return the next generation's symbols, gain-descending, at most [#MAX_SYMBOLS] of them
    static List<Symbol> run(Compressor current, Sample sample, int chunkLimit,
                            int sampleFractionNumerator, boolean finalGeneration) {
        Counts counts = compressCount(current, sample, chunkLimit);
        return makeTable(counts, sampleFractionNumerator, finalGeneration);
    }

    /// Compress-counts the first `chunkLimit` chunks of `sample`: greedily parses each chunk with
    /// `current`, tallying single-match counts and adjacent-pair counts. Candidates are keyed by
    /// packed symbol so both the parsed symbols and the input bytes they cover are captured directly
    /// from the sample (generation 0's empty table escapes everything, seeding single bytes).
    private static Counts compressCount(Compressor current, Sample sample, int chunkLimit) {
        Counts counts = new Counts();
        byte[] bytes = sample.bytes();
        for (int c = 0; c < chunkLimit; c++) {
            int start = sample.chunkStart(c);
            int end = sample.chunkEnd(c);
            long previous = -1L; // No previous match within this chunk yet.
            int pos = start;
            while (pos < end) {
                int matchLength = matchLengthAt(current, bytes, pos, end);
                long packed = Compressor.loadWord(bytes, pos, pos + matchLength) & lengthMask(matchLength);
                counts.bumpSingle(packed, matchLength);
                if (previous >= 0) {
                    counts.bumpPair(previous, packed);
                }
                previous = packed;
                pos += matchLength;
            }
        }
        return counts;
    }

    /// Returns the length of the current table's longest match at `pos`, or 1 when nothing matches
    /// (an escaped single byte still advances one position and counts as a length-1 candidate).
    ///
    /// An over-long match is rejected the same way [Compressor#compress(byte[], int, int, byte[],
    /// long)] rejects it: loadWord zero-pads past `end`, so a symbol with trailing zero bytes can
    /// spuriously match the padding beyond the chunk. Counting such a match would tally a candidate
    /// spanning bytes that are not really in the sample, and could advance `pos` past `end`.
    private static int matchLengthAt(Compressor current, byte[] bytes, int pos, int end) {
        int packedMatch = current.matcher().longestMatch(Compressor.loadWord(bytes, pos, end));
        int length = Matcher.lengthOf(packedMatch);
        return length > 0 && pos + length <= end ? length : 1;
    }

    /// Builds the next table from the counted candidates: proposes each single symbol and each
    /// adjacent-pair concatenation, prunes by min-count, boosts single-byte gain, ranks, applies the
    /// final cost prune on the last generation, and keeps the top [#MAX_SYMBOLS] by gain.
    private static List<Symbol> makeTable(Counts counts, int sampleFractionNumerator,
                                          boolean finalGeneration) {
        // Refinement 2 — per-generation min-count pruning. A candidate seen fewer times than this
        // floor is noise at this generation's sample coverage and is dropped before its gain is even
        // computed. The floor scales with the sample fraction so early, low-coverage generations
        // prune more aggressively; the final generation relaxes it to 1 so rare-but-real candidates
        // survive the last, authoritative ranking pass.
        int minCount = finalGeneration
                ? 1
                : Math.max(1, 5 * sampleFractionNumerator / Sample.SAMPLE_FRACTION_DENOMINATOR);

        List<Candidate> candidates = new ArrayList<>();
        counts.forEachSingle((packed, length, count) -> {
            if (count >= minCount) {
                candidates.add(new Candidate(packed, length, gainOf(count, length)));
            }
        });
        counts.forEachPair((first, second, count) -> {
            if (count < minCount) {
                return;
            }
            int firstLength = counts.lengthOf(first);
            int combinedLength = Math.min(firstLength + counts.lengthOf(second), MAX_SYMBOL_LENGTH);
            long combined = concatenate(first, firstLength, second);
            long packed = combined & lengthMask(combinedLength);
            candidates.add(new Candidate(packed, combinedLength, gainOf(count, combinedLength)));
        });

        return selectTop(candidates, finalGeneration);
    }

    /// Ranks candidates by gain-descending (length-descending on ties) and keeps the top
    /// [#MAX_SYMBOLS], applying the final cost prune on the last generation. Uses a bounded min-heap
    /// so a large candidate set is not fully sorted — only the surviving 255 are.
    private static List<Symbol> selectTop(List<Candidate> candidates, boolean finalGeneration) {
        // A bounded min-heap of size MAX_SYMBOLS: the weakest survivor sits at the head, so a new
        // candidate either loses to it (discarded) or evicts it. This is top-K in O(n log K) rather
        // than an O(n log n) full sort of every candidate.
        // Min-heap on ranking gain: the weakest survivor sits at the head.
        PriorityQueue<Candidate> heap = new PriorityQueue<>(TrainingGeneration::compareGain);
        for (Candidate candidate : candidates) {
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
            if (result.size() >= MAX_SYMBOLS) {
                break;
            }
            // Refinement 4 — final cost-based prune. Only on the last generation, a symbol earns its
            // code slot only if its real (un-boosted) gain exceeds length+1: it must save at least
            // that many bytes over escaping every occurrence, otherwise the slot is better left for a
            // candidate that does. Provisional earlier generations skip this so their symbols stay
            // available to be re-evaluated next pass.
            if (finalGeneration && realGain(candidate) <= candidate.length() + 1L) {
                continue;
            }
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

    /// Tallies of single-match and adjacent-pair counts during compress-count. Single matches are
    /// keyed by packed symbol (its length is stored alongside); pairs are keyed by the ordered
    /// `(firstPacked, secondPacked)` pair.
    private static final class Counts {

        private final Map<Long, long[]> singles = new HashMap<>(); // packed -> {count, length}
        private final Map<Pair, Long> pairs = new HashMap<>();

        void bumpSingle(long packed, int length) {
            singles.merge(packed, new long[]{1, length}, (existing, added) -> {
                existing[0]++;
                return existing;
            });
        }

        void bumpPair(long first, long second) {
            pairs.merge(new Pair(first, second), 1L, Long::sum);
        }

        int lengthOf(long packed) {
            return (int) singles.get(packed)[1];
        }

        void forEachSingle(SingleConsumer consumer) {
            for (Map.Entry<Long, long[]> entry : singles.entrySet()) {
                long[] value = entry.getValue();
                consumer.accept(entry.getKey(), (int) value[1], value[0]);
            }
        }

        void forEachPair(PairConsumer consumer) {
            for (Map.Entry<Pair, Long> entry : pairs.entrySet()) {
                Pair pair = entry.getKey();
                consumer.accept(pair.first(), pair.second(), entry.getValue());
            }
        }
    }

    private record Pair(long first, long second) {
    }

    @FunctionalInterface
    private interface SingleConsumer {
        void accept(long packed, int length, long count);
    }

    @FunctionalInterface
    private interface PairConsumer {
        void accept(long first, long second, long count);
    }
}
