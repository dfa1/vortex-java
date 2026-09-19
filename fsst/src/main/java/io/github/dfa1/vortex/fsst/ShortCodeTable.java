package io.github.dfa1.vortex.fsst;

import java.util.Arrays;
import java.util.List;

/// Direct-indexed table resolving the shortest FSST matches — length 0 (no match), 1, or 2 —
/// from the first two bytes of an input word (the FSST paper's Algorithm 4 `shortCodes`).
///
/// The key is the input word's low 16 bits read as an unsigned little-endian value, i.e. the first
/// two bytes of the input at the current position (byte 0 in the low 8 bits, byte 1 next). That key
/// indexes one of 65536 slots, each holding a packed `code << 8 | length` giving the matched symbol
/// code and its length in one array read — no hashing, no probing.
///
/// Only length-1 and length-2 symbols populate the table; longer symbols are the
/// [LossyPerfectHashTable]'s job — though this class also records, per two-byte key, whether such a
/// longer symbol exists at all, so [Matcher] can skip that table's probe when none can match (see
/// [#mayHaveLongerMatch(long)]). Each 16-bit key `hi:lo` is seeded so that, absent a longer match,
/// it resolves to the length-1 symbol for its low byte `lo` (if any). A length-2 symbol then
/// overwrites the specific `hi:lo` slot for its exact two bytes, taking precedence over the
/// length-1 fallback. A key whose low byte has no length-1 symbol and no length-2 symbol resolves
/// to [#NO_CODE] with length 0, telling the caller to escape that single byte.
final class ShortCodeTable {

    /// Number of slots — one per possible 16-bit (two-byte) key.
    private static final int SLOTS = 1 << 16;

    /// Sentinel code meaning "no symbol matched"; the caller must escape the current byte. Real
    /// codes are `0..254` (`0xFF` is the escape), so `-1` can never collide with a real code.
    static final int NO_CODE = -1;

    /// Packed no-match value stored in unpopulated slots: `NO_CODE << 8 | 0`. Storing the sentinel
    /// pre-packed lets [#packedFor(long)] return the slot verbatim — one array read, no branch —
    /// and an arithmetic `>> 8` recovers [#NO_CODE] while `& 0xFF` recovers length 0.
    private static final int NO_MATCH = NO_CODE << 8;

    /// Packed `code << 8 | length` per 16-bit key. A zero length marks "no match" ([#NO_MATCH]).
    private final int[] table;

    /// One bit per 16-bit key: set when some symbol of length 3-8 starts with those two bytes, i.e.
    /// when a [LossyPerfectHashTable] probe at this position could possibly match. This is the
    /// Rust reference's `has_suffix_code` predicate — it reaches the same decision through a code
    /// renumbering (`2(no-suffix) | 2(suffix) | 3..8 | 1`, so one integer compare answers it),
    /// which this implementation cannot reuse because its codes are numbered by training gain and
    /// then permuted into wire order independently.
    ///
    /// A 3-8 byte symbol can only match at a position whose first two bytes equal the symbol's
    /// own, so a clear bit proves no hash-table entry can match and the probe is pure waste. The
    /// converse is not required: the bit may be set while the probe still misses (the hash table
    /// is lossy and drops symbols on collision), which costs a wasted probe, never a wrong answer.
    ///
    /// 65536 bits = 8 KB, so this stays L1-resident next to a 256 KB [#table] whose probe it
    /// avoids.
    private final long[] longerPrefix;

    private ShortCodeTable(int[] table, long[] longerPrefix) {
        this.table = table;
        this.longerPrefix = longerPrefix;
    }

    /// Builds the table from symbols in descending-gain order, keeping only the length-1 and
    /// length-2 entries. The list index is each symbol's code, matching the parallel-array
    /// convention [Decompressor#of(long[], int[])] uses (array index is the code).
    ///
    /// Length-1 symbols are seeded first across all 256 high-byte keys that share their low byte,
    /// so any two-byte prefix falls back to its low byte's single-byte code; length-2 symbols then
    /// overwrite their exact key, taking precedence. Input order beyond that does not matter here:
    /// a length-2 symbol owns a unique key, so there is no gain-order contention within this table
    /// (unlike [LossyPerfectHashTable], where collisions make insertion order load-bearing).
    ///
    /// @param symbolsByGainDescending the trained symbols, code = list index, gain-descending
    /// @return a table resolving 0/1/2-byte matches for any two-byte input prefix
    static ShortCodeTable of(List<Symbol> symbolsByGainDescending) {
        return of(symbolsByGainDescending, (ShortCodeTable) null);
    }

    /// Same as [#of(List)], but re-seeds `reuse`'s backing arrays in place instead of allocating
    /// fresh ones, avoiding a repeated 256 KB + 8 KB allocation when many tables are built in a
    /// tight sequence (training rebuilds one per generation). Taking the previous table rather than
    /// its raw array keeps both arrays reused together — an earlier version handed over only the
    /// `int[]` and silently re-allocated the prefix bitset on every generation. Safe only once
    /// `reuse` is never read again — see [Matcher#rebuild(List, Matcher)].
    ///
    /// @param symbolsByGainDescending the trained symbols, code = list index, gain-descending
    /// @param reuse a table whose arrays this call overwrites, or `null` to allocate fresh
    /// @return a table resolving 0/1/2-byte matches for any two-byte input prefix
    static ShortCodeTable of(List<Symbol> symbolsByGainDescending, ShortCodeTable reuse) {
        int[] table = reuse != null ? reuse.table : new int[SLOTS];
        Arrays.fill(table, NO_MATCH);
        long[] longerPrefix = reuse != null ? reuse.longerPrefix : new long[SLOTS / Long.SIZE];
        // Stale bits would only ever cause a redundant probe, never a wrong match, but they
        // accumulate across generations and would erode the skip back to always probing.
        Arrays.fill(longerPrefix, 0L);
        for (Symbol symbol : symbolsByGainDescending) {
            if (symbol.length() >= 3) {
                int key = (int) (symbol.packedBytes() & 0xFFFF);
                longerPrefix[key >>> 6] |= 1L << key;
            }
        }
        for (int code = 0; code < symbolsByGainDescending.size(); code++) {
            Symbol symbol = symbolsByGainDescending.get(code);
            if (symbol.length() == 1) {
                int low = symbol.byteAt(0) & 0xFF;
                int packed = code << 8 | 1;
                for (int high = 0; high < 256; high++) {
                    int key = high << 8 | low;
                    if (length(table[key]) == 0) {
                        table[key] = packed;
                    }
                }
            }
        }
        for (int code = 0; code < symbolsByGainDescending.size(); code++) {
            Symbol symbol = symbolsByGainDescending.get(code);
            if (symbol.length() == 2) {
                int key = (int) (symbol.packedBytes() & 0xFFFF);
                table[key] = code << 8 | 2;
            }
        }
        return new ShortCodeTable(table, longerPrefix);
    }

    /// Returns whether any stored 3-8 byte symbol starts with `word`'s first two bytes, i.e.
    /// whether a [LossyPerfectHashTable] probe here could match at all.
    ///
    /// `false` is a proof of absence: the caller may skip the probe entirely. `true` is only a
    /// possibility, so the caller must still probe and handle a miss.
    ///
    /// @param word an input word; only its low 16 bits (first two input bytes) are consulted
    /// @return `false` when no 3-8 byte symbol can match at this position
    boolean mayHaveLongerMatch(long word) {
        int key = (int) (word & 0xFFFF);
        return (longerPrefix[key >>> 6] >>> key & 1L) != 0;
    }

    /// Returns the match for the low two bytes of `word` as `code << 8 | length`, or
    /// `NO_CODE << 8` (length 0) when there is no length-1 or length-2 match. One array read, no
    /// branching — the slot value is returned verbatim, which is what makes this the hot path's
    /// fallback of choice.
    ///
    /// @param word an input word; only its low 16 bits (first two input bytes) are consulted
    /// @return the match as `code << 8 | length`; length 0 (and code [#NO_CODE]) means no match
    int packedFor(long word) {
        return table[(int) (word & 0xFFFF)];
    }

    private static int length(int packed) {
        return packed & 0xFF;
    }

}
