package io.github.dfa1.vortex.fsst;

/// A single FSST symbol: up to 8 bytes packed LSB-first into a `long` (byte 0 in the low byte,
/// byte `k` at bit position `k * 8`) paired with an explicit length in `1..8`.
///
/// The LSB-first packing convention is shared everywhere a symbol crosses a boundary — the
/// training tables, the compressor, the decompressor, and eventually the `vortex.fsst` wire
/// format — so a symbol packed here reads back byte-for-byte identically wherever it lands.
///
/// @param packedBytes the symbol's bytes, LSB-first (byte `k` at bit position `k * 8`)
/// @param length the symbol length in bytes, in `1..8`
public record Symbol(long packedBytes, int length) {

    /// Maximum symbol length in bytes, bounded by what fits in one `long`.
    private static final int MAX_LENGTH = 8;

    /// Validates that `length` is a legal FSST symbol length.
    ///
    /// @throws IllegalArgumentException if `length` is not in `1..8`
    public Symbol {
        if (length < 1 || length > MAX_LENGTH) {
            throw new IllegalArgumentException("symbol length must be in 1..8, was " + length);
        }
    }

    /// Packs `length` bytes of `data` starting at `offset` LSB-first into a new symbol.
    ///
    /// @param data the source bytes
    /// @param offset the index of the symbol's first byte in `data`
    /// @param length the symbol length in bytes, in `1..8`
    /// @return a symbol holding the `length` bytes at `data[offset .. offset + length)`
    public static Symbol of(byte[] data, int offset, int length) {
        long value = 0;
        for (int k = 0; k < length; k++) {
            value |= Byte.toUnsignedLong(data[offset + k]) << (k * 8);
        }
        return new Symbol(value, length);
    }

    /// Returns the `i`-th byte of this symbol (0-indexed, so byte 0 is the LSB).
    ///
    /// @param i the byte index, in `0 .. length - 1`
    /// @return the `i`-th byte of the symbol
    public byte byteAt(int i) {
        return (byte) (packedBytes >>> (i * 8));
    }
}
