package io.github.dfa1.vortex.fsst;

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
/// [LossyPerfectHashTable]'s job. Each 16-bit key `hi:lo` is seeded so that, absent a longer match,
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

    /// Packed `code << 8 | length` per 16-bit key. A zero length marks "no match".
    private final int[] slots;

    private ShortCodeTable(int[] slots) {
        this.slots = slots;
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
        int[] slots = new int[SLOTS];
        for (int code = 0; code < symbolsByGainDescending.size(); code++) {
            Symbol symbol = symbolsByGainDescending.get(code);
            if (symbol.length() == 1) {
                int low = symbol.byteAt(0) & 0xFF;
                int packed = code << 8 | 1;
                for (int high = 0; high < 256; high++) {
                    int key = high << 8 | low;
                    if (length(slots[key]) == 0) {
                        slots[key] = packed;
                    }
                }
            }
        }
        for (int code = 0; code < symbolsByGainDescending.size(); code++) {
            Symbol symbol = symbolsByGainDescending.get(code);
            if (symbol.length() == 2) {
                int key = (int) (symbol.packedBytes() & 0xFFFF);
                slots[key] = code << 8 | 2;
            }
        }
        return new ShortCodeTable(slots);
    }

    /// Returns the code matched by the low two bytes of `word`, or [#NO_CODE] if none. One array
    /// read, no branching.
    ///
    /// @param word an input word; only its low 16 bits (first two input bytes) are consulted
    /// @return the matched symbol code, or [#NO_CODE] when there is no length-1 or length-2 match
    int codeFor(long word) {
        int packed = slots[(int) (word & 0xFFFF)];
        return packed == 0 ? NO_CODE : packed >>> 8;
    }

    /// Returns the length of the symbol matched by the low two bytes of `word`: 2, 1, or 0 for no
    /// match. One array read, no branching.
    ///
    /// @param word an input word; only its low 16 bits (first two input bytes) are consulted
    /// @return the matched symbol length in bytes, or 0 when there is no match
    int lengthFor(long word) {
        return length(slots[(int) (word & 0xFFFF)]);
    }

    private static int length(int packed) {
        return packed & 0xFF;
    }
}
