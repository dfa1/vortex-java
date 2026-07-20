package io.github.dfa1.vortex.fsst;

import java.util.List;

/// Branch-free longest-match matcher composing a [LossyPerfectHashTable] (3-8 byte candidates) with
/// a [ShortCodeTable] (0/1/2 byte candidates), the FSST paper's Algorithm 4.
///
/// This replaces the old encoder's `longestMatch`, which probed symbol lengths 8 down to 1 in a
/// per-position loop (up to eight sequential hash lookups per input byte). Here a single hash-table
/// lookup plus one masked compare yields the 3-8 byte candidate, a single array read yields the
/// 0/1/2 byte candidate, and one conditional select picks the longer — no loop over candidate
/// lengths, no per-length branch. That uniform body is exactly what the hot-loop rule (no
/// modulo/division/variable-target branch per element) needs to stay JIT-vectorizable, which the
/// old eight-iteration loop could not deliver.
public final class Matcher {

    /// Longest symbol length that can be resolved, in bytes.
    private static final int MAX_SYMBOL_LENGTH = 8;

    private final LossyPerfectHashTable hashTable;
    private final ShortCodeTable shortCodes;

    private Matcher(LossyPerfectHashTable hashTable, ShortCodeTable shortCodes) {
        this.hashTable = hashTable;
        this.shortCodes = shortCodes;
    }

    /// Builds a matcher from the trained symbols in descending-gain order.
    ///
    /// The list index is each symbol's code, matching the parallel-array convention
    /// [Decompressor#of(long[], int[])] uses. The whole list is handed to both tables: each keeps
    /// only the entries of its own length class ([ShortCodeTable] takes lengths 1-2,
    /// [LossyPerfectHashTable] takes lengths 3-8), so a symbol's code stays equal to its index in
    /// the original list without any placeholder padding. The caller's gain-descending order is
    /// preserved, since it is load-bearing for the hash table's first-writer-wins collision
    /// handling. The input is not re-sorted.
    ///
    /// @param symbolsByGainDescending the trained symbols, code = list index, gain-descending
    /// @return a matcher resolving the longest 0-8 byte match at any input position
    public static Matcher of(List<Symbol> symbolsByGainDescending) {
        return new Matcher(
                LossyPerfectHashTable.of(symbolsByGainDescending),
                ShortCodeTable.of(symbolsByGainDescending));
    }

    /// Returns the longest match at the current input position packed as `code << 8 | length`.
    ///
    /// The hash table is consulted first for a 3-8 byte candidate; on a real hit that candidate is
    /// returned, otherwise the result falls back to the short-code table's 0/1/2 byte answer. A
    /// length of 0 (and code [ShortCodeTable#NO_CODE]) means no symbol matched and the caller must
    /// escape the current byte. Callers that want the parts separately can use
    /// [#codeOf(int)] and [#lengthOf(int)].
    ///
    /// @param word an 8-byte little-endian input word starting at the current match position, with
    ///             any bytes past the remaining input already zero-padded by the caller
    /// @return the longest match as `code << 8 | length`; length 0 signals "no match, escape"
    public int longestMatch(long word) {
        LossyPerfectHashTable.HashMatch hashMatch = hashTable.lookup(word);
        if (hashMatch.hit()) {
            return hashMatch.code() << 8 | hashMatch.length();
        }
        return shortCodes.codeFor(word) << 8 | shortCodes.lengthFor(word);
    }

    /// Extracts the code from a packed [#longestMatch(long)] result.
    ///
    /// @param packedMatch a `code << 8 | length` value from [#longestMatch(long)]
    /// @return the matched symbol code, or [ShortCodeTable#NO_CODE] when the length is 0
    public static int codeOf(int packedMatch) {
        return packedMatch >> 8;
    }

    /// Extracts the length from a packed [#longestMatch(long)] result.
    ///
    /// @param packedMatch a `code << 8 | length` value from [#longestMatch(long)]
    /// @return the matched symbol length in bytes, 1-8, or 0 when there is no match
    public static int lengthOf(int packedMatch) {
        return packedMatch & 0xFF;
    }

    /// Longest symbol length resolvable by this matcher, in bytes.
    ///
    /// @return the maximum symbol length, 8
    public static int maxSymbolLength() {
        return MAX_SYMBOL_LENGTH;
    }
}
