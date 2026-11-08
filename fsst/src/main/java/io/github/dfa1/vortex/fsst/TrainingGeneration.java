package io.github.dfa1.vortex.fsst;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// One generation of FSST bottom-up training (the FSST paper's Algorithm 3), a port of Rust's
/// `fsst-rs` `Compressor::train` loop body (`builder.rs`): compress-count the generation's lines with
/// the current symbol table, then build the next table from the highest-gain candidates.
///
/// Compress-count parses each line with the current [Compressor], longest match first, tallying how
/// often each match occurs (`count1`) and each adjacent pair of matches (`count2`); a match of more
/// than one byte also counts its first byte alone, as the extension a single-byte symbol could
/// make. Counts are keyed by Rust's extended codes: a byte no symbol matches is its own value
/// `0..255`, and the symbol of code `k` is `256 + k`.
///
/// Make-table turns every counted code into a candidate, the symbol itself and, until the last
/// generation, its merges with the codes seen after it, and sums the gains of candidates that are the
/// same symbol. The candidates are taken best first, by gain, then length, then the symbol's bytes
/// (fsst-rs 0.7, whose ordering no longer depends on its hash map), and the first 255 that fit become
/// the table.
final class TrainingGeneration {

    /// Maximum number of real symbols kept per generation (codes `0..254`; `0xFF` is the escape).
    private static final int MAX_SYMBOLS = 255;

    /// Maximum symbol length in bytes, bounded by what fits in one `long`.
    private static final int MAX_SYMBOL_LENGTH = 8;

    /// Rust's `FSST_CODE_BASE`: the extended code of symbol 0. Codes below it are bytes no symbol
    /// matched.
    private static final int CODE_BASE = 256;

    /// Rust's `FSST_CODE_MASK`: the extended code before any match in a line, never a real code.
    private static final int NO_CODE = 511;

    /// Size of the code space, Rust's `COUNTS1_SIZE`.
    private static final int CODE_SPACE = 512;

    /// Single-byte gain multiplier: a single byte in the table replaces a 2-byte escape on every
    /// occurrence, so frequent bytes are worth more than their raw `count * length`. The C++
    /// reference's heuristic.
    private static final int SINGLE_BYTE_GAIN_BOOST = 8;

    private TrainingGeneration() {
    }

    /// Runs one training generation and returns the next generation's symbols in the order they
    /// were taken (code = list index), ready to build the next [Compressor].
    ///
    /// @param current  the current generation's compressor (an empty one for generation 0, so every
    ///                 position escapes and bootstraps the single-byte candidates)
    /// @param sample   the training sample
    /// @param fraction this generation's sample fraction numerator over
    ///                 [Sample#FRACTION_DENOMINATOR]: it picks the lines replayed, the count floor, and
    ///                 whether merges are made (not in the last generation)
    /// @param counts   reusable counting storage, cleared at the end of this call
    /// @return the next generation's symbols, at most [#MAX_SYMBOLS] of them
    static List<Symbol> run(Compressor current, Sample sample, int fraction, Counts counts) {
        byte[] bytes = sample.bytes();
        for (int line = 0; line < sample.lineCount(); line++) {
            // Rust's per-line skip: a generation replays about fraction/128 of the lines
            if (fraction < Sample.FRACTION_DENOMINATOR && (Sample.hash(line) & 127) > fraction) {
                continue;
            }
            compressCount(current, bytes, sample.lineStart(line), sample.lineEnd(line), counts);
        }
        // the last generation of a corpus taken whole is pruned, one that was sampled is not
        boolean prune = fraction >= Sample.FRACTION_DENOMINATOR && !sample.sampled();
        List<Symbol> next = optimize(current, counts, fraction, prune);
        counts.clear();
        return next;
    }

    private static void compressCount(Compressor current, byte[] bytes, int start, int end, Counts counts) {
        int previousCode = NO_CODE;
        int pos = start;
        int fastEnd = end - 8;
        while (pos < end) {
            long word = pos <= fastEnd
                    ? (long) Compressor.LONG_LE_BYTES.get(bytes, pos)
                    : Compressor.loadWord(bytes, pos, end);
            int packedMatch = current.matcher().longestMatch(word);
            int length = Matcher.lengthOf(packedMatch);
            int code;
            if (length > 0) {
                code = CODE_BASE + Matcher.codeOf(packedMatch);
            } else {
                code = (int) (word & 0xFF);
                length = 1;
            }
            counts.bumpSingle(code);
            counts.bumpPair(previousCode, code);
            if (length > 1) {
                // also the extension by just this symbol's first byte
                int firstByte = (int) (word & 0xFF);
                counts.bumpSingle(firstByte);
                counts.bumpPair(previousCode, firstByte);
            }
            previousCode = code;
            pos += length;
        }
    }

    /// Rust's `optimize`: the candidates from the counts, then the best 255 that fit in the table.
    private static List<Symbol> optimize(Compressor current, Counts counts, int fraction, boolean prune) {
        Map<Long, Long> gains = gains(current, counts, fraction, prune);
        Candidate[] candidates = new Candidate[gains.size()];
        int index = 0;
        for (Map.Entry<Long, Long> entry : gains.entrySet()) {
            candidates[index++] = new Candidate(entry.getValue(), lengthOf(entry.getKey()), entry.getKey());
        }
        Arrays.sort(candidates, Candidate.BEST_FIRST);
        return select(candidates, prune);
    }

    /// Each candidate symbol's gain, keyed by the symbol's bytes: the same symbol reached through
    /// different codes (a byte that escaped and the symbol that is just that byte) adds up its gains.
    private static Map<Long, Long> gains(Compressor current, Counts counts, int fraction, boolean prune) {
        Map<Long, Long> gains = new LinkedHashMap<>();
        // Rust's integer division: 0 at the first generation, where every count is at least 1
        int minCount = prune ? 1 : 5 * fraction / Sample.FRACTION_DENOMINATOR;
        // no merges in the last generation
        boolean merge = fraction < Sample.FRACTION_DENOMINATOR;
        for (int code1 = counts.nextSingle(0); code1 >= 0 && code1 < NO_CODE; code1 = counts.nextSingle(code1 + 1)) {
            long count = counts.single(code1);
            if (count >= minCount) {
                long symbol1 = symbolOf(current, code1);
                int length1 = lengthOf(symbol1);
                long gain = count * length1;
                if (length1 == 1) {
                    gain *= SINGLE_BYTE_GAIN_BOOST;
                }
                gains.merge(symbol1, gain, Long::sum);
                // nor merges of a symbol that cannot grow
                if (merge && length1 < MAX_SYMBOL_LENGTH) {
                    addMerges(gains, current, counts, code1, symbol1, length1);
                }
            }
        }
        return gains;
    }

    /// The gains of `code1`'s symbol merged with each code counted after it, where the two fit in 8 bytes.
    private static void addMerges(Map<Long, Long> gains, Compressor current, Counts counts, int code1, long symbol1,
                                  int length1) {
        for (int code2 = counts.nextPair(code1, 0); code2 >= 0 && code2 < NO_CODE;
             code2 = counts.nextPair(code1, code2 + 1)) {
            long symbol2 = symbolOf(current, code2);
            if (length1 + lengthOf(symbol2) <= MAX_SYMBOL_LENGTH) {
                long merged = symbol1 | symbol2 << (8 * length1);
                gains.merge(merged, counts.pair(code1, code2) * lengthOf(merged), Long::sum);
            }
        }
    }

    /// The first 255 candidates, best first, that are worth their table entry when `prune` is set
    /// and whose hash slot is free.
    private static List<Symbol> select(Candidate[] candidates, boolean prune) {
        BitSet takenSlots = new BitSet();
        List<Symbol> result = new ArrayList<>(MAX_SYMBOLS);
        for (int i = 0; i < candidates.length && result.size() < MAX_SYMBOLS; i++) {
            Candidate candidate = candidates[i];
            if (prune && !savesMoreThanItCosts(candidate)) {
                continue;
            }
            // a 3-8 byte symbol whose hash slot a better one holds is skipped, and the next
            // candidate takes its code
            if (candidate.length() < 3 || claimSlot(takenSlots, candidate.symbol())) {
                result.add(new Symbol(candidate.symbol(), candidate.length()));
            }
        }
        return result;
    }

    /// Whether a symbol saves more bytes than its table entry costs: its length plus one.
    private static boolean savesMoreThanItCosts(Candidate candidate) {
        long saves = candidate.gain();
        if (candidate.length() == 1) {
            saves /= SINGLE_BYTE_GAIN_BOOST;
        }
        return saves > candidate.length() + 1;
    }

    /// The symbol of an extended code: a byte no symbol matched, or the current table's symbol.
    private static long symbolOf(Compressor current, int code) {
        if (code < CODE_BASE) {
            return code;
        }
        return current.packedSymbol(code - CODE_BASE);
    }

    /// Rust's `Symbol::len`: the bytes up to the last non-zero one, at least one. A symbol that ends in
    /// zero bytes is the shorter one, and a zero byte alone is one byte.
    private static int lengthOf(long symbol) {
        return Math.max(1, MAX_SYMBOL_LENGTH - Long.numberOfLeadingZeros(symbol) / 8);
    }

    private static boolean claimSlot(BitSet takenSlots, long packed) {
        int slot = LossyPerfectHashTable.slotFor(packed);
        if (takenSlots.get(slot)) {
            return false;
        }
        takenSlots.set(slot);
        return true;
    }

    /// Rust's `Candidate`: a symbol and the bytes it would save. Candidates are taken best first by
    /// gain, then length, then the symbol's packed bytes as an unsigned `u64` (fsst-rs 0.7's
    /// `Candidate::cmp`). The symbols are distinct, so no two candidates tie and the order is total.
    ///
    /// @param gain   the bytes the symbol saves over the sample
    /// @param length the symbol's length in bytes
    /// @param symbol the symbol's bytes, packed LSB-first
    record Candidate(long gain, int length, long symbol) {

        /// Highest gain first, then the longer symbol, then the larger packed bytes.
        static final Comparator<Candidate> BEST_FIRST = Comparator.comparingLong(Candidate::gain)
                .thenComparingInt(Candidate::length)
                .thenComparing(Candidate::symbol, Long::compareUnsigned)
                .reversed();
    }

    /// Match counts and adjacent-pair counts over the extended code space, flat arrays with no
    /// hashing, reused across generations: a [BitSet] per array tracks the slots a generation
    /// touched, so clearing resets only that.
    static final class Counts {

        private final long[] counts1 = new long[CODE_SPACE];
        private final long[] counts2 = new long[CODE_SPACE * CODE_SPACE];
        private final BitSet singleTouched = new BitSet(CODE_SPACE);
        private final BitSet pairTouched = new BitSet(CODE_SPACE * CODE_SPACE);

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

        long single(int code) {
            return counts1[code];
        }

        long pair(int firstCode, int secondCode) {
            return counts2[firstCode * CODE_SPACE + secondCode];
        }

        /// The first counted code at or after `from`, in ascending order, or -1.
        int nextSingle(int from) {
            return singleTouched.nextSetBit(from);
        }

        /// The first code at or after `from` counted as following `firstCode`, in ascending order, or
        /// -1.
        int nextPair(int firstCode, int from) {
            int idx = pairTouched.nextSetBit(firstCode * CODE_SPACE + from);
            if (idx < 0 || idx >= (firstCode + 1) * CODE_SPACE) {
                return -1;
            }
            return idx - firstCode * CODE_SPACE;
        }
    }
}
