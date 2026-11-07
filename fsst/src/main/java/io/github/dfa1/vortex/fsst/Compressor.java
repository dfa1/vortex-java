package io.github.dfa1.vortex.fsst;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
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

    /// Little-endian `long` layout for `MemorySegment` access. Defined locally rather than reusing
    /// `core`'s `VortexFormat.LE_LONG` so this module keeps its zero dependency on `core`; the FSST
    /// wire format packs symbol bytes LSB-first, so all `MemorySegment` reads/writes are little-endian.
    static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /// Little-endian unaligned `long` view over a `byte[]`, the JIT-intrinsified way to load or
    /// store an 8-byte word in one instruction on the `byte[]` compress fast path (the byte-by-byte
    /// [#loadWord(byte[], int, int)] assembly is 8 loads + shifts and is kept for the tail only).
    /// Package-private: [Decompressor] reuses this for its own `byte[]`-output store fast path,
    /// same convention as [Decompressor]'s [#LE_LONG] reuse for `MemorySegment` access.
    static final VarHandle LONG_LE_BYTES =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /// Slots of the symbol table Rust's builder keeps, whose real symbols are the first `symbolCount()`.
    private static final int SYMBOL_SLOTS = 255;

    private final List<Symbol> symbolsByGainDescending;
    private final Matcher matcher;
    /// The bytes of each of the table's [#SYMBOL_SLOTS] slots as Rust's builder holds them: the real
    /// symbols, then, past them, whatever an earlier generation left there, which Rust never clears.
    private final long[] slots;

    private Compressor(List<Symbol> symbolsByGainDescending, Matcher matcher, long[] slots) {
        this.symbolsByGainDescending = symbolsByGainDescending;
        this.matcher = matcher;
        this.slots = slots;
    }

    private static long[] slotsOf(List<Symbol> symbols, long[] previous) {
        long[] slots = previous.clone();
        for (int code = 0; code < symbols.size(); code++) {
            slots[code] = symbols.get(code).packedBytes();
        }
        return slots;
    }

    /// Creates a compressor over the given symbols, which must be in gain-descending order (code =
    /// list index). The list is copied defensively so the compressor stays immutable.
    ///
    /// @param symbolsByGainDescending the trained symbols, code = list index, gain-descending
    /// @return a compressor bound to that table
    static Compressor of(List<Symbol> symbolsByGainDescending) {
        List<Symbol> copy = List.copyOf(symbolsByGainDescending);
        return new Compressor(copy, Matcher.of(copy), slotsOf(copy, new long[SYMBOL_SLOTS]));
    }

    /// Rebuilds a compressor from new symbols, reusing `previous`'s [Matcher] backing arrays instead
    /// of allocating fresh ones (issue #393 #7). Safe only once `previous` is discarded — see
    /// [Matcher#rebuild(List, Matcher)].
    ///
    /// @param symbolsByGainDescending the newly trained symbols, code = list index, gain-descending
    /// @param previous the compressor whose matcher this rebuild re-seeds in place
    /// @return a compressor bound to the given table, backed by `previous`'s (now overwritten)
    ///         matcher arrays
    static Compressor rebuild(List<Symbol> symbolsByGainDescending, Compressor previous) {
        List<Symbol> copy = List.copyOf(symbolsByGainDescending);
        return new Compressor(copy, Matcher.rebuild(copy, previous.matcher), slotsOf(copy, previous.slots));
    }

    /// Returns an equivalent compressor whose codes are permuted: the symbol currently at code
    /// `newOrder[i]` becomes code `i`. [#compress] then emits `newOrder`'s numbering directly,
    /// letting a wire adapter that needs its own on-wire code order (e.g. length-sorted, via
    /// [#codesSortedByLength()]) get it without a second pass remapping every emitted code byte
    /// after compression (issue #393 #13).
    ///
    /// Reuses this compressor's own matcher backing arrays (the same mechanism as [#rebuild]), so
    /// this compressor must not be used again after calling this method — read whatever you need
    /// from it (e.g. [#packedSymbol(int)] / [#symbolLength(int)] for a symbol table) first.
    ///
    /// @param newOrder a permutation of `0 .. symbolCount() - 1`; `newOrder[i]` is this compressor's
    ///                  current code that becomes code `i` in the result
    /// @return a compressor over the same symbols, renumbered per `newOrder`
    public Compressor withCodeOrder(int[] newOrder) {
        List<Symbol> reordered = new ArrayList<>(newOrder.length);
        for (int oldCode : newOrder) {
            reordered.add(symbolsByGainDescending.get(oldCode));
        }
        return rebuild(reordered, this);
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

    /// Returns a permutation of the codes in the order Rust's `fsst-rs` numbers them once its table is
    /// final (`finalize`): the 2-byte symbols that no other symbol shares its first two bytes with, in
    /// the order they were taken; then the 2-byte symbols some longer symbol starts with, last taken
    /// first; then the 3, 4, ..., 8-byte symbols, each length in the order they were taken; then the
    /// 1-byte symbols.
    ///
    /// The split of the 2-byte symbols lets Rust's compressor skip its hash table for a 2-byte code
    /// below the split, which no longer symbol can extend. Rust asks "does another symbol start with
    /// these two bytes" of every slot of its table, including the ones an earlier training generation
    /// left behind past the final symbols, so a table smaller than an earlier one splits differently
    /// than its own symbols alone would.
    ///
    /// @return an `int[]` permutation of `0 .. symbolCount() - 1`: the code at each position
    public int[] codesSortedByLength() {
        int maxSymbolLength = Matcher.maxSymbolLength();
        int n = symbolCount();
        int[] countByLength = new int[maxSymbolLength + 1]; // index 1..maxSymbolLength; 0 unused
        for (int code = 0; code < n; code++) {
            countByLength[symbolLength(code)]++;
        }

        // First position of each length: 1-byte symbols after all the others; the 2-byte ones from 0,
        // split at the suffix limit; then each longer length after the one before.
        int[] nextPosition = new int[maxSymbolLength + 1];
        nextPosition[1] = n - countByLength[1];
        nextPosition[2] = 0;
        for (int length = 2; length < maxSymbolLength; length++) {
            nextPosition[length + 1] = nextPosition[length] + countByLength[length];
        }
        int noSuffix = 0;
        int hasSuffix = nextPosition[3];

        int[] result = new int[n];
        for (int code = 0; code < n; code++) {
            int length = symbolLength(code);
            int position;
            if (length == 2) {
                if (sharesFirstTwoBytes(code)) {
                    hasSuffix--;
                    position = hasSuffix;
                } else {
                    position = noSuffix++;
                }
            } else {
                position = nextPosition[length]++;
            }
            result[position] = code;
        }
        return result;
    }

    /// Whether another slot starts with the same two bytes as the 2-byte symbol `code`: as Rust's
    /// `finalize` asks it, of every slot of its table, the ones past the real symbols too, with what
    /// an earlier generation left in them.
    private boolean sharesFirstTwoBytes(int code) {
        long first2 = slots[code] & 0xFFFF;
        for (int other = 0; other < SYMBOL_SLOTS; other++) {
            if (other != code && (slots[other] & 0xFFFF) == first2) {
                return true;
            }
        }
        return false;
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
        // Fast path: while 8 real input bytes remain, load the word in one intrinsified read. No
        // over-long-match guard is needed here — a match is at most 8 bytes, so it can never reach
        // past end while pos <= end - 8.
        int fastEnd = end - 8;
        while (pos <= fastEnd) {
            long word = (long) LONG_LE_BYTES.get(input, pos);
            int packedMatch = matcher.longestMatch(word);
            int length = Matcher.lengthOf(packedMatch);
            if (length > 0) {
                out[outIndex++] = (byte) Matcher.codeOf(packedMatch);
                pos += length;
            } else {
                // The escaped literal is the word's low byte — already loaded, no re-read.
                out[outIndex++] = (byte) ESCAPE;
                out[outIndex++] = (byte) word;
                pos++;
            }
        }
        while (pos < end) {
            long word = loadWord(input, pos, end);
            int packedMatch = matcher.longestMatch(word);
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
                out[outIndex++] = (byte) word;
                pos++;
            }
        }
        return outIndex;
    }

    /// Compresses the byte range `[start, end)` of `input` into the `MemorySegment` `out` using
    /// longest-match-first greedy parsing, writing the code stream starting at `outPos`.
    ///
    /// Mixes [#compress(byte[], int, int, byte[], long)]'s fast intrinsified `byte[]` word load
    /// with a `MemorySegment` destination — the shape a caller writing straight into an
    /// arena-allocated output segment from `byte[]` row data needs (e.g. `FsstEncodingEncoder`),
    /// without wrapping the input in a `MemorySegment` first and paying `MemorySegment.get`'s
    /// access overhead on every word load instead of the intrinsified `byte[]` `VarHandle` read.
    ///
    /// @param input the raw bytes to compress
    /// @param start the index of the first byte to compress, inclusive
    /// @param end the index one past the last byte to compress, exclusive
    /// @param out the destination segment for the code stream
    /// @param outPos the index in `out` at which to start writing
    /// @return the index in `out` one past the last byte written
    public long compress(byte[] input, int start, int end, MemorySegment out, long outPos) {
        int pos = start;
        long outIndex = outPos;
        // Fast path: while 8 real input bytes remain, load the word in one intrinsified read. No
        // over-long-match guard is needed here — a match is at most 8 bytes, so it can never reach
        // past end while pos <= end - 8.
        int fastEnd = end - 8;
        while (pos <= fastEnd) {
            long word = (long) LONG_LE_BYTES.get(input, pos);
            int packedMatch = matcher.longestMatch(word);
            int length = Matcher.lengthOf(packedMatch);
            if (length > 0) {
                out.set(ValueLayout.JAVA_BYTE, outIndex++, (byte) Matcher.codeOf(packedMatch));
                pos += length;
            } else {
                // The escaped literal is the word's low byte — already loaded, no re-read.
                out.set(ValueLayout.JAVA_BYTE, outIndex++, (byte) ESCAPE);
                out.set(ValueLayout.JAVA_BYTE, outIndex++, (byte) word);
                pos++;
            }
        }
        while (pos < end) {
            long word = loadWord(input, pos, end);
            int packedMatch = matcher.longestMatch(word);
            int length = Matcher.lengthOf(packedMatch);
            // Reject an over-long match: loadWord zero-pads bytes past end, and the branch-free
            // Matcher has no notion of end, so a symbol whose trailing bytes are zero can spuriously
            // satisfy the masked compare against the padding and report a match longer than the real
            // remaining input. Trusting it would emit one code that decodes to more bytes than were
            // compressed, silently corrupting the tail of any row (NUL-containing or binary data
            // especially). Falling back to an escape keeps the byte count exact.
            if (length > 0 && pos + length <= end) {
                out.set(ValueLayout.JAVA_BYTE, outIndex++, (byte) Matcher.codeOf(packedMatch));
                pos += length;
            } else {
                out.set(ValueLayout.JAVA_BYTE, outIndex++, (byte) ESCAPE);
                out.set(ValueLayout.JAVA_BYTE, outIndex++, (byte) word);
                pos++;
            }
        }
        return outIndex;
    }

    /// Compresses the byte range `[start, end)` of the `MemorySegment` `input` into `out` using
    /// longest-match-first greedy parsing, writing the code stream starting at `outPos`.
    ///
    /// This is the `MemorySegment`-native hot path, matching the FFM-everywhere convention at this
    /// module's boundary; it mirrors [#compress(byte[], int, int, byte[], long)] exactly, including
    /// the `pos + length <= end` guard that rejects an over-long spurious match against zero-padded
    /// bytes past the input end. Dropping that guard would let a trained symbol whose trailing bytes
    /// are zero satisfy the branch-free [Matcher]'s masked compare against the padding and decode to
    /// more bytes than were compressed, corrupting the tail of any NUL-containing or binary row.
    ///
    /// The caller must size `out` so every code fits: a range of `end - start` input bytes expands to
    /// at most `2 * (end - start)` output bytes (a full run of escapes). This is an internal
    /// algorithm boundary, not an untrusted-input boundary, so no defensive bounds checks are
    /// performed here.
    ///
    /// @param input the raw bytes to compress
    /// @param start the index of the first byte to compress, inclusive
    /// @param end the index one past the last byte to compress, exclusive
    /// @param out the destination segment for the code stream
    /// @param outPos the index in `out` at which to start writing
    /// @return the index in `out` one past the last byte written
    public long compress(MemorySegment input, long start, long end, MemorySegment out, long outPos) {
        long pos = start;
        long outIndex = outPos;
        // Fast path: while 8 readable bytes remain (bounded by both end and the segment's own
        // size — the segment may end exactly at end with no trailing slack), load the word in one
        // unaligned read. No over-long-match guard is needed here — a match is at most 8 bytes.
        long fastEnd = Math.min(end, input.byteSize()) - 8;
        while (pos <= fastEnd) {
            long word = input.get(LE_LONG, pos);
            int packedMatch = matcher.longestMatch(word);
            int length = Matcher.lengthOf(packedMatch);
            if (length > 0) {
                out.set(ValueLayout.JAVA_BYTE, outIndex++, (byte) Matcher.codeOf(packedMatch));
                pos += length;
            } else {
                // The escaped literal is the word's low byte — already loaded, no re-read.
                out.set(ValueLayout.JAVA_BYTE, outIndex++, (byte) ESCAPE);
                out.set(ValueLayout.JAVA_BYTE, outIndex++, (byte) word);
                pos++;
            }
        }
        while (pos < end) {
            long word = loadWord(input, pos, end);
            int packedMatch = matcher.longestMatch(word);
            int length = Matcher.lengthOf(packedMatch);
            // Same boundary guard as the byte[] overload: reject a match that reaches past the real
            // input end, since loadWord zero-pads and the branch-free Matcher has no notion of end.
            if (length > 0 && pos + length <= end) {
                out.set(ValueLayout.JAVA_BYTE, outIndex++, (byte) Matcher.codeOf(packedMatch));
                pos += length;
            } else {
                out.set(ValueLayout.JAVA_BYTE, outIndex++, (byte) ESCAPE);
                out.set(ValueLayout.JAVA_BYTE, outIndex++, (byte) word);
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

    /// Loads an 8-byte little-endian word from the `MemorySegment` `input` starting at `pos`,
    /// zero-padding any bytes at or past `end` so the [Matcher]'s masked compare never reads across
    /// the range boundary.
    ///
    /// Unlike the `byte[]` overload — which may safely over-read within a same-array bounds check — a
    /// wide unconditional 8-byte read is not safe here: `input` may represent exactly one row's range
    /// with no guaranteed trailing slack past `end`, so reading past the segment's own allocated
    /// bounds would fault. The word is therefore assembled byte by byte, bounded by whichever of `end`
    /// and the segment's own size comes first.
    ///
    /// @param input the source segment
    /// @param pos the first byte to load
    /// @param end the exclusive range end; bytes at or after it read as zero
    /// @return the 8-byte little-endian word at `pos`
    static long loadWord(MemorySegment input, long pos, long end) {
        long word = 0;
        long limit = Math.min(pos + 8, Math.min(end, input.byteSize()));
        for (long k = pos; k < limit; k++) {
            word |= Byte.toUnsignedLong(input.get(ValueLayout.JAVA_BYTE, k)) << ((k - pos) * 8);
        }
        return word;
    }
}
