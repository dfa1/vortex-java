package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Little-endian bit reader over a [MemorySegment].
///
/// Bits are packed LSB-first within each byte (pcodec wire format convention).
/// Bit 0 of the stream is the LSB of byte 0; bit 8 is the LSB of byte 1.
public final class LeBitReader {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private final MemorySegment data;
    private long bitPos;

    /// Wrap `data` for LSB-first sequential reads from bit position 0.
    ///
    /// @param data backing segment
    public LeBitReader(MemorySegment data) {
        this.data = data;
        this.bitPos = 0;
    }

    /// Read `n` bits (0 ≤ n ≤ 64) from the stream, LSB-first.
    ///
    /// @param n bit count, 0..64 inclusive
    /// @return value with low `n` bits set from the stream
    public long readBits(int n) {
        if (n == 0) {
            return 0L;
        }
        long result = 0L;
        int remaining = n;
        int shift = 0;
        while (remaining > 0) {
            int byteIndex = (int) (bitPos >>> 3);
            int bitInByte = (int) (bitPos & 7);
            int available = 8 - bitInByte;
            int take = Math.min(remaining, available);
            int b;
            try {
                b = data.get(BYTE, byteIndex) & 0xFF;
            } catch (IndexOutOfBoundsException e) {
                throw new VortexException("pco: truncated data at bit " + bitPos, e);
            }
            result |= (long) ((b >>> bitInByte) & ((1 << take) - 1)) << shift;
            shift += take;
            remaining -= take;
            bitPos += take;
        }
        return result;
    }

    /// Discard bits to align the stream to the next byte boundary.
    public void alignToByte() {
        int bitsInCurrentByte = (int) (bitPos & 7);
        if (bitsInCurrentByte != 0) {
            bitPos += 8 - bitsInCurrentByte;
        }
    }

    /// Current byte offset (only meaningful after [#alignToByte()]).
    ///
    /// @return current stream position in bytes
    public long byteOffset() {
        return bitPos >>> 3;
    }

    /// @return total bits consumed since construction
    public long bitsConsumed() {
        return bitPos;
    }
}
