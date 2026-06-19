package io.github.dfa1.vortex.writer.encode;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/// Little-endian bit writer backed by a growable byte buffer.
///
/// Bits are packed LSB-first within each byte — the symmetric counterpart to
/// `io.github.dfa1.vortex.reader.decode.LeBitReader`.
final class LeBitWriter {

    private byte[] buffer;
    private int bytePos;
    private int currentByte;
    private int bitsInCurrentByte;

    LeBitWriter(int initialCapacityBytes) {
        buffer = new byte[Math.max(initialCapacityBytes, 1)];
    }

    /// Write `n` bits (0 ≤ n ≤ 64) from `value`, LSB-first.
    ///
    /// @param value source bits; only the low `n` bits are written
    /// @param n     bit count, 0..64 inclusive
    void writeBits(long value, int n) {
        if (n == 0) {
            return;
        }
        int remaining = n;
        int shift = 0;
        while (remaining > 0) {
            int available = 8 - bitsInCurrentByte;
            int take = Math.min(remaining, available);
            int bits = (int) ((value >>> shift) & ((1L << take) - 1));
            currentByte |= bits << bitsInCurrentByte;
            bitsInCurrentByte += take;
            shift += take;
            remaining -= take;
            if (bitsInCurrentByte == 8) {
                flush();
            }
        }
    }

    /// Pad with zero bits to the next byte boundary.
    void alignToByte() {
        if (bitsInCurrentByte > 0) {
            flush();
        }
    }

    /// Copy buffered bytes into an arena-allocated [MemorySegment].
    ///
    /// @param arena allocator whose lifetime must exceed all uses of the returned segment
    /// @return a new segment containing every byte written so far
    MemorySegment toMemorySegment(Arena arena) {
        alignToByte();
        MemorySegment seg = arena.allocate(bytePos);
        MemorySegment.copy(MemorySegment.ofArray(buffer), 0L, seg, 0L, bytePos);
        return seg;
    }

    private void flush() {
        if (bytePos >= buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        buffer[bytePos++] = (byte) currentByte;
        currentByte = 0;
        bitsInCurrentByte = 0;
    }
}
