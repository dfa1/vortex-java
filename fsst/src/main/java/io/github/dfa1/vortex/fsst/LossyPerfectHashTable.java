package io.github.dfa1.vortex.fsst;

import java.util.List;

/// Lossy perfect hash table resolving 3-8 byte FSST matches from the first three bytes of an input
/// word (the FSST paper's Algorithm 4, §5.1 "Predicated Scalar Compression").
///
/// Each slot holds at most one candidate symbol. A lookup hashes the input word's first three bytes
/// to exactly one slot, reads it (no probing, no chaining), and confirms the match with a single
/// masked 8-byte compare: the input word's high bits beyond the candidate's length are masked off,
/// then compared against the candidate's packed bytes. A slot miss or a failed compare is a "no
/// match" — this is what "lossy" means: a genuine 3-8 byte match can occasionally miss because a
/// higher-gain symbol won its slot on a hash collision. That is harmless, because greedy parsing
/// then falls back to a shorter match ([ShortCodeTable]) or an escape — never to wrong output.
final class LossyPerfectHashTable {

    /// Number of slots. The paper specifies 4096; the well-regarded Rust reference
    /// `spiraldb/fsst` uses 2048, citing avoidance of L1D cache-line splits (each slot is small, so
    /// 2048 slots keep the whole table within a handful of cache lines that a scan touches
    /// repeatedly), and 2048 is this project's benchmark target (`vortex-jni`) — so we deliberately
    /// match the reference here rather than the paper's literal 4096. A power of two lets the hash
    /// mask instead of taking a modulo, which the hot-loop rule (no per-element modulo/division)
    /// requires.
    private static final int SLOTS = 2048;

    /// Mask selecting a slot index from a hash; valid because [#SLOTS] is a power of two.
    private static final int SLOT_MASK = SLOTS - 1;

    /// Multiply-xor mixing constant, the same shape as the old encoder's `SymbolTable.indexHash`
    /// (the golden-ratio 64-bit odd constant `2^64 / phi`). Multiplying by a large odd constant and
    /// folding the high bits down spreads the low three input bytes across the whole word so the
    /// slot mask sees well-mixed bits, avoiding clustering when many symbols share a low byte.
    private static final long HASH_MULTIPLIER = 0x9E3779B97F4A7C15L;

    /// Low three bytes of the input word — the only bytes the hash keys on.
    private static final long PREFIX_MASK = 0x00FF_FFFFL;

    /// Slot table with two adjacent longs per slot (the FSST paper's C layout): `slots[2 * s]` is
    /// the candidate's packed symbol bytes (LSB-first, [Symbol] convention) and `slots[2 * s + 1]`
    /// is its metadata `ignoredBits << 16 | code << 8 | length` (`ignoredBits = 64 - 8 * length`).
    /// A lookup derives the keep-mask from the ignored-bits with one shift instead of loading a
    /// separate mask array, so the whole 16-byte slot lands in a single cache line — one memory
    /// touch per lookup instead of three parallel-array reads across three lines.
    ///
    /// Empty slots are all-zero: metadata 0 makes the keep-mask `~0L` and the symbol 0, so an
    /// input word of exactly 0 can "hit" an empty slot — harmlessly, because the returned low 16
    /// bits (`code << 8 | length`) are then 0, which is precisely the "no match" answer. No
    /// occupancy flag or sentinel is needed.
    private final long[] slots;

    private LossyPerfectHashTable(long[] slots) {
        this.slots = slots;
    }

    /// Builds the table from the trained symbols in descending-gain order, keeping only those of
    /// length 3-8 (shorter symbols are the [ShortCodeTable]'s job and are skipped here). The list
    /// index is each symbol's code, matching the parallel-array convention
    /// [Decompressor#of(long[], int[])] uses.
    ///
    /// Insertion is a single forward pass with first-writer-wins on collision. WHY this is
    /// load-bearing: the caller passes symbols in descending gain order, so when two symbols' first
    /// three bytes hash to the same slot, the one seen first (the higher-gain one) keeps the slot
    /// and the later (lower-gain) one is skipped, never overwritten. This is exactly the paper's
    /// rule that the more valuable symbol wins a lossy collision. This class must NOT re-sort its
    /// input — it consumes whatever order the caller gives and inserts once.
    ///
    /// @param symbolsByGainDescending the trained symbols, code = list index, gain-descending
    /// @return a hash table resolving 3-8 byte matches with first-writer-wins on collision
    static LossyPerfectHashTable of(List<Symbol> symbolsByGainDescending) {
        long[] slots = new long[2 * SLOTS];
        for (int code = 0; code < symbolsByGainDescending.size(); code++) {
            Symbol symbol = symbolsByGainDescending.get(code);
            if (symbol.length() < 3) {
                continue; // Length 1-2 belongs to ShortCodeTable.
            }
            int slot = slotFor(symbol.packedBytes());
            if (slots[2 * slot + 1] != 0) {
                continue; // First writer (higher gain) wins; skip the collision.
            }
            long ignoredBits = 64L - 8 * symbol.length();
            slots[2 * slot] = symbol.packedBytes();
            slots[2 * slot + 1] = ignoredBits << 16 | (long) code << 8 | symbol.length();
        }
        return new LossyPerfectHashTable(slots);
    }

    /// Looks up `word` and returns the matched symbol as `code << 8 | length`, or 0 when no stored
    /// 3-8 byte symbol matches.
    ///
    /// The input word's first three bytes select one slot; the candidate there matches only if the
    /// masked compare passes (`(word & (~0L >>> ignoredBits)) == candidate.packedBytes`), which
    /// rules out hash collisions with unrelated bytes. An empty slot can only "hit" an all-zero
    /// input word, and then still answers 0 (its metadata is 0), which is the no-match result. A
    /// stored symbol's length is 3-8, so a real hit is never 0 and the caller can test the result
    /// directly. On a miss the caller falls back to [ShortCodeTable].
    ///
    /// @param word an 8-byte little-endian input word starting at the current match position, with
    ///             any bytes past the remaining input already zero-padded by the caller
    /// @return the match as `code << 8 | length`, or 0 when there is no match
    int lookup(long word) {
        int slot = slotFor(word) << 1;
        long symbol = slots[slot];
        long meta = slots[slot + 1];
        return (word & (~0L >>> (int) (meta >>> 16))) == symbol ? (int) (meta & 0xFFFF) : 0;
    }

    /// Computes the slot index a word hashes to, keyed on its first three bytes. Package-visible so
    /// tests can construct deliberate hash collisions against a stable, inspectable hash rather than
    /// blindly brute-forcing one — a stable hash is easier to reason about and to test.
    ///
    /// @param word an input word; only its low three bytes are hashed
    /// @return the slot index in `0 .. SLOTS - 1`
    static int slotFor(long word) {
        long mixed = (word & PREFIX_MASK) * HASH_MULTIPLIER;
        return (int) (mixed >>> 32) & SLOT_MASK;
    }

}
