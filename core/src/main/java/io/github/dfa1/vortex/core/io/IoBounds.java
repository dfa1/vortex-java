package io.github.dfa1.vortex.core.io;

import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;

/// Bounds-checked access to untrusted [MemorySegment] regions.
///
/// The reader memory-maps and parses attacker-controlled binary input. Per the
/// [VortexException] contract, any malformed offset, length, or element count
/// must surface as a [VortexException] — never a raw JDK
/// [IndexOutOfBoundsException], [ArithmeticException], or
/// [NegativeArraySizeException]. Offsets and lengths drawn from parsed file
/// bytes flow through these helpers before they reach the JDK.
///
/// This covers the *parse* side only. A caller's random-access index into a
/// decoded array (`array.getInt(5)`) is a different contract: that is consumer
/// misuse and should throw [IndexOutOfBoundsException] via
/// [java.util.Objects#checkIndex(long, long)], not a `VortexException`.
///
/// See ADR 0003 (`docs/adr/0003-vortex-exception-sanitization.md`).
public final class IoBounds {

    private IoBounds() {
    }

    /// Verifies that the range `[off, off + len)` lies within `[0, size]`.
    ///
    /// @param off  start offset into the region
    /// @param len  length of the range
    /// @param size total size of the region the range must fit within
    /// @throws VortexException if `off` or `len` is negative, or `off + len`
    ///         exceeds `size` (overflow-safe: checks `len > size - off`)
    public static void checkRange(long off, long len, long size) {
        if (off < 0 || len < 0 || len > size - off) {
            throw new VortexException(
                    "slice out of bounds: off=" + off + " len=" + len + " size=" + size);
        }
    }

    /// Bounds-checked [MemorySegment#asSlice(long, long)] — the canonical
    /// replacement for a raw `asSlice` on an untrusted offset or length.
    ///
    /// @param seg the segment to slice
    /// @param off start offset into `seg`
    /// @param len length of the slice
    /// @return the slice `seg[off, off + len)`
    /// @throws VortexException if the range falls outside `seg`
    public static MemorySegment slice(MemorySegment seg, long off, long len) {
        checkRange(off, len, seg.byteSize());
        return seg.asSlice(off, len);
    }

    /// Narrows a `long` size or count to `int` for use as a [java.nio.ByteBuffer]
    /// index or a Java array length. Replaces [Math#toIntExact(long)] (which
    /// throws [ArithmeticException]) and guards the 2 GB `ByteBuffer` / array cap.
    ///
    /// @param n the size or count, drawn from parsed file metadata
    /// @return `n` as an `int`
    /// @throws VortexException if `n` is negative or exceeds [Integer#MAX_VALUE]
    public static int toIntSize(long n) {
        if (n < 0 || n > Integer.MAX_VALUE) {
            throw new VortexException("size exceeds 2 GB limit: " + n);
        }
        return (int) n;
    }

    /// Validates an element count before a `new T[count]` decode allocation.
    ///
    /// Same guard as [#toIntSize(long)], named for the allocation call sites; a
    /// per-encoding resource cap (ADR 0004) plugs in here later.
    ///
    /// @param n the element count, drawn from parsed file metadata
    /// @return `n` as an `int`
    /// @throws VortexException if `n` is negative or exceeds [Integer#MAX_VALUE]
    public static int checkCount(long n) {
        return toIntSize(n);
    }
}
