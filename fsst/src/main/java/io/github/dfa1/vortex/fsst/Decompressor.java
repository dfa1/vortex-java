package io.github.dfa1.vortex.fsst;

/// Decodes an FSST-compressed byte stream against a trained symbol table (the FSST paper's
/// Algorithm 1).
///
/// The table is held as parallel arrays indexed by code: `packedSymbols` gives each code's bytes
/// packed LSB-first into a `long` (the [Symbol] convention) and `lengths` gives its byte length.
/// Decoding walks the compressed stream one code at a time: the escape code `0xFF` copies the
/// next literal byte verbatim, any other code appends its symbol's bytes to the output.
public final class Decompressor {

    /// Escape code: emitted as `0xFF` followed by one literal byte, which is copied to the
    /// output unchanged. Real symbol codes are therefore `0..254`.
    public static final int ESCAPE = 0xFF;

    private final long[] packedSymbols;
    private final int[] lengths;

    private Decompressor(long[] packedSymbols, int[] lengths) {
        this.packedSymbols = packedSymbols;
        this.lengths = lengths;
    }

    /// Creates a decompressor over a trained symbol table.
    ///
    /// The two arrays are indexed by code and must be the same length; the arrays are referenced,
    /// not copied, so the caller must not mutate them afterwards.
    ///
    /// @param packedSymbols each code's bytes packed LSB-first into a `long`, indexed by code
    /// @param lengths each code's symbol length in bytes, indexed by code
    /// @return a decompressor bound to the given table
    public static Decompressor of(long[] packedSymbols, int[] lengths) {
        return new Decompressor(packedSymbols, lengths);
    }

    /// Decodes the compressed byte range `[start, end)` of `compressed` into `out`, writing the
    /// decoded bytes starting at `outPos`.
    ///
    /// The caller is responsible for sizing `out` so every decoded byte fits: an `end - start`
    /// code stream expands to at most `8 * (end - start)` output bytes (a full run of length-8
    /// symbols). This is an internal algorithm boundary, not an untrusted-input boundary, so no
    /// defensive bounds checks are performed here.
    ///
    /// @param compressed the compressed code stream
    /// @param start the index of the first code to decode, inclusive
    /// @param end the index one past the last code to decode, exclusive
    /// @param out the destination buffer for decoded bytes
    /// @param outPos the index in `out` at which to start writing
    /// @return the index in `out` one past the last byte written
    public long decompress(byte[] compressed, int start, int end, byte[] out, long outPos) {
        int pos = start;
        int outIndex = (int) outPos;
        while (pos < end) {
            int code = compressed[pos++] & 0xFF;
            if (code == ESCAPE) {
                out[outIndex++] = compressed[pos++];
            } else {
                long packed = packedSymbols[code];
                int length = lengths[code];
                for (int k = 0; k < length; k++) {
                    out[outIndex++] = (byte) (packed >>> (k * 8));
                }
            }
        }
        return outIndex;
    }
}
