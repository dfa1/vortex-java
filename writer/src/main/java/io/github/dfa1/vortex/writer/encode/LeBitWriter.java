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
    // Widest write that fits the accumulator next to up to 7 pending bits.
    private static final int MAX_SHORT_BITS = 56;

    private byte[] buffer;
    private int bytePos;
    // Pending bits of the byte at bytePos, LSB first: always fewer than 8 between calls.
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
        if (n > MAX_SHORT_BITS) {
            writeShortBits(value, 32);
            writeShortBits(value >>> 32, n - 32);
        } else {
            writeShortBits(value, n);
        }
    }

    // Branch-free: the bit widths of ANS states and offsets are data-dependent, so a flush branch
    // mispredicts. Stores the whole accumulator every time (bytes past the pending bits are zeros
    // over not-yet-written bytes) and keeps only the partial byte.
    private void writeShortBits(long value, int n) {
        ensureCapacity(8);
        pending |= (value & ((1L << n) - 1)) << pendingBits;
        LE_LONG.set(buffer, bytePos, pending);
        int total = pendingBits + n;
        bytePos += total >>> 3;
        pending >>>= total & ~7;
        pendingBits = total & 7;
    }

    /// Write `values[i]` in `widths[i]` bits for each `i` in `[from, to)`, as [#writeBits(long, int)]
    /// would one by one.
    ///
    /// The writer state lives in locals for the whole run (Rust's `write_short_uints` does the same):
    /// kept in fields, each write reloaded what the previous one had just stored, a store-to-load
    /// forward on the loop-carried path.
    ///
    /// @param values source bits; only the low `widths[i]` bits of each are written
    /// @param widths bit count per value, 0..64 inclusive
    /// @param from   first index, inclusive
    /// @param to     last index, exclusive
    void writeBits(long[] values, int[] widths, int from, int to) {
        ensureCapacity((to - from) * 8 + 8);
        byte[] buf = buffer;
        long acc = pending;
        int accBits = pendingBits;
        int pos = bytePos;
        for (int i = from; i < to; i++) {
            long value = values[i];
            int n = widths[i];
            if (n > MAX_SHORT_BITS) {
                acc |= (value & 0xFFFF_FFFFL) << accBits;
                LE_LONG.set(buf, pos, acc);
                int total = accBits + 32;
                pos += total >>> 3;
                acc >>>= total & ~7;
                accBits = total & 7;
                value >>>= 32;
                n -= 32;
            }
            acc |= (value & ((1L << n) - 1)) << accBits;
            LE_LONG.set(buf, pos, acc);
            int total = accBits + n;
            pos += total >>> 3;
            acc >>>= total & ~7;
            accBits = total & 7;
        }
        pending = acc;
        pendingBits = accBits;
        bytePos = pos;
    }

    /// Forget everything written, keeping the buffer: bytes are overwritten, never OR-ed, so stale
    /// bytes past the write position never leak into later output.
    void reset() {
        bytePos = 0;
        pending = 0;
        pendingBits = 0;
    }

    /// Pad with zero bits to the next byte boundary.
    void alignToByte() {
        if (pendingBits > 0) {
            // the partial byte is already in the buffer, stored by the write that left it pending
            bytePos++;
            pending = 0;
            pendingBits = 0;
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

    private void ensureCapacity(int extra) {
        if (bytePos + extra > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, bytePos + extra));
        }
    }
}
