package io.github.dfa1.vortex.fsst;

import java.util.ArrayList;
import java.util.List;

/// An immutable, trained FSST symbol table together with the branch-free [Matcher] built from it.
///
/// A compressor is produced by [CompressorBuilder#train(byte[][])] and holds up to 255 symbols
/// (codes `0..254`; `0xFF` is the escape). The symbols are retained in gain-descending order, which
/// is load-bearing: [Matcher#of(List)] relies on that order for its lossy-hash first-writer-wins
/// collision rule, so a symbol's code equals its index in the retained list.
///
/// The table can be handed straight to a [Decompressor] via [#toDecompressor()], and reordered into
/// the length-sorted permutation the `vortex.fsst` wire format wants via [#codesSortedByLength()].
public final class Compressor {

    /// Escape code: emitted as `0xFF` followed by one literal byte. Real symbol codes are `0..254`.
    static final int ESCAPE = Decompressor.ESCAPE;

    private final List<Symbol> symbolsByGainDescending;
    private final Matcher matcher;

    private Compressor(List<Symbol> symbolsByGainDescending, Matcher matcher) {
        this.symbolsByGainDescending = symbolsByGainDescending;
        this.matcher = matcher;
    }

    /// Creates a compressor over the given symbols, which must be in gain-descending order (code =
    /// list index). The list is copied defensively so the compressor stays immutable.
    ///
    /// @param symbolsByGainDescending the trained symbols, code = list index, gain-descending
    /// @return a compressor bound to that table
    static Compressor of(List<Symbol> symbolsByGainDescending) {
        List<Symbol> copy = List.copyOf(symbolsByGainDescending);
        return new Compressor(copy, Matcher.of(copy));
    }

    /// Returns the branch-free matcher over this table, used by training's compress-count pass.
    ///
    /// @return the matcher built from this compressor's symbols
    Matcher matcher() {
        return matcher;
    }

    /// Returns the number of symbols in the table (0-255).
    ///
    /// @return the symbol count
    public int symbolCount() {
        return symbolsByGainDescending.size();
    }

    /// Returns the bytes of the symbol with the given code, packed LSB-first into a `long` (the
    /// [Symbol] convention).
    ///
    /// @param code the symbol code, in `0 .. symbolCount() - 1`
    /// @return the symbol's packed bytes
    public long packedSymbol(int code) {
        return symbolsByGainDescending.get(code).packedBytes();
    }

    /// Returns the length in bytes (1-8) of the symbol with the given code.
    ///
    /// @param code the symbol code, in `0 .. symbolCount() - 1`
    /// @return the symbol's length in bytes
    public int symbolLength(int code) {
        return symbolsByGainDescending.get(code).length();
    }

    /// Returns a permutation of the codes ordered by symbol length: all multi-byte symbols (length
    /// 2-8) first in non-decreasing length order, then all length-1 symbols last.
    ///
    /// This is pure, wire-format-agnostic reordering logic — "sort by length, single-byte-last" —
    /// with no `vortex.fsst`-specific naming or semantics baked in. The `vortex.fsst` wire adapter
    /// (a separate module) happens to interpret this exact permutation as its on-wire symbol order,
    /// but that interpretation lives entirely in the adapter; the algorithm module only knows how to
    /// sort. Ties within a length bucket keep their gain-descending relative order (a stable sort),
    /// so the permutation is deterministic.
    ///
    /// @return an `int[]` permutation of `0 .. symbolCount() - 1` in length-sorted, single-byte-last
    ///         order
    public int[] codesSortedByLength() {
        List<Integer> codes = new ArrayList<>(symbolCount());
        for (int code = 0; code < symbolCount(); code++) {
            codes.add(code);
        }
        codes.sort((a, b) -> {
            int lengthA = symbolLength(a);
            int lengthB = symbolLength(b);
            boolean singleA = lengthA == 1;
            boolean singleB = lengthB == 1;
            if (singleA != singleB) {
                return singleA ? 1 : -1;
            }
            return Integer.compare(lengthA, lengthB);
        });
        int[] result = new int[codes.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = codes.get(i);
        }
        return result;
    }

    /// Builds a [Decompressor] over this compressor's table so the same trained symbols round-trip
    /// without re-deriving the parallel arrays at the call site.
    ///
    /// @return a decompressor bound to this table
    public Decompressor toDecompressor() {
        int count = symbolCount();
        long[] packed = new long[count];
        int[] lengths = new int[count];
        for (int code = 0; code < count; code++) {
            packed[code] = packedSymbol(code);
            lengths[code] = symbolLength(code);
        }
        return Decompressor.of(packed, lengths);
    }

    /// Compresses the byte range `[start, end)` of `input` into `out` using longest-match-first
    /// greedy parsing, writing the code stream starting at `outPos`.
    ///
    /// At each position the [Matcher] resolves the longest matching symbol; a match emits its
    /// one-byte code and advances by the symbol length, while a position matching no symbol emits an
    /// escape (`0xFF` followed by the literal byte). The caller must size `out` so every code fits:
    /// a range of `end - start` input bytes expands to at most `2 * (end - start)` output bytes (a
    /// full run of escapes). This is an internal algorithm boundary, not an untrusted-input
    /// boundary, so no defensive bounds checks are performed here.
    ///
    /// @param input the raw bytes to compress
    /// @param start the index of the first byte to compress, inclusive
    /// @param end the index one past the last byte to compress, exclusive
    /// @param out the destination buffer for the code stream
    /// @param outPos the index in `out` at which to start writing
    /// @return the index in `out` one past the last byte written
    public long compress(byte[] input, int start, int end, byte[] out, long outPos) {
        int pos = start;
        int outIndex = (int) outPos;
        while (pos < end) {
            int packedMatch = matcher.longestMatch(loadWord(input, pos, end));
            int length = Matcher.lengthOf(packedMatch);
            // Reject an over-long match: loadWord zero-pads bytes past end, and the branch-free
            // Matcher has no notion of end, so a symbol whose trailing bytes are zero can spuriously
            // satisfy the masked compare against the padding and report a match longer than the real
            // remaining input. Trusting it would emit one code that decodes to more bytes than were
            // compressed, silently corrupting the tail of any row (NUL-containing or binary data
            // especially). Falling back to an escape keeps the byte count exact.
            if (length > 0 && pos + length <= end) {
                out[outIndex++] = (byte) Matcher.codeOf(packedMatch);
                pos += length;
            } else {
                out[outIndex++] = (byte) ESCAPE;
                out[outIndex++] = input[pos];
                pos++;
            }
        }
        return outIndex;
    }

    /// Loads an 8-byte little-endian word from `input` starting at `pos`, zero-padding any bytes at
    /// or past `end` so the [Matcher]'s masked compare never reads across the range boundary.
    ///
    /// @param input the source bytes
    /// @param pos the first byte to load
    /// @param end the exclusive range end; bytes at or after it read as zero
    /// @return the 8-byte little-endian word at `pos`
    static long loadWord(byte[] input, int pos, int end) {
        long word = 0;
        int available = Math.min(8, end - pos);
        for (int k = 0; k < available; k++) {
            word |= Byte.toUnsignedLong(input[pos + k]) << (k * 8);
        }
        return word;
    }
}
