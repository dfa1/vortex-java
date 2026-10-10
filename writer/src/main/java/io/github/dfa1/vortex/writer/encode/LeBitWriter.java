package io.github.dfa1.vortex.writer.encode;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/// Little-endian bit writer backed by a growable byte buffer.
///
/// Bits are packed LSB-first within each byte — the symmetric counterpart to
/// `io.github.dfa1.vortex.reader.decode.LeBitReader`.
final class LeBitWriter {

    private static final VarHandle LE_LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LE_INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private byte[] buffer;
    private int bytePos;
    // Pending bits, LSB first: always fewer than 32 between calls, so one more write of up to 64 bits fits
    // the accumulator or spills at most 31 bits into the next one.
    private long pending;
    private int pendingBits;

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
        long bits = n < 64 ? value & ((1L << n) - 1) : value;
        pending |= bits << pendingBits;
        int total = pendingBits + n;
        if (total >= 64) {
            ensureCapacity(8);
            LE_LONG.set(buffer, bytePos, pending);
            bytePos += 8;
            // pendingBits is 0 only when n is 64, which leaves nothing over
            pending = pendingBits == 0 ? 0 : bits >>> (64 - pendingBits);
            total -= 64;
        } else if (total >= 32) {
            ensureCapacity(4);
            LE_INT.set(buffer, bytePos, (int) pending);
            bytePos += 4;
            pending >>>= 32;
            total -= 32;
        }
        pendingBits = total;
    }

    /// Pad with zero bits to the next byte boundary.
    void alignToByte() {
        int bytes = (pendingBits + 7) >>> 3;
        ensureCapacity(bytes);
        for (int i = 0; i < bytes; i++) {
            buffer[bytePos++] = (byte) (pending >>> (8 * i));
        }
        pending = 0;
        pendingBits = 0;
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

    private void ensureCapacity(int extra) {
        if (bytePos + extra > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, bytePos + extra));
        }
    }
}
