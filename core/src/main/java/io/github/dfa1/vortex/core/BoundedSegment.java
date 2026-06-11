package io.github.dfa1.vortex.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// A memory-mapped region with built-in bounds-checking for slicing on untrusted input.
///
/// <p>By construction, callers cannot reach {@link MemorySegment#asSlice(long, long)} without
/// going through {@link #slice(long, long, String)}, which routes the offset/length through
/// {@link MemorySegments#slice} and throws {@link VortexException} on malformed input —
/// never {@link IndexOutOfBoundsException}.
///
/// <p>The {@code context} label travels with the type; nested slices receive an explicit
/// child label at the {@link #slice} site. Error messages thus name the on-disk structure
/// ({@code "trailer"}, {@code "postscript blob"}, {@code "encoded buffer 3"}) rather than
/// surfacing raw byte offsets.
///
/// <p>The raw segment is exposed only via {@link #unwrapForSubParser(String)}, which both
/// documents the trust transfer and forces a {@code reason} string so every escape-hatch
/// site is greppable for audit.
///
/// @param seg     the backing memory-mapped region; lifetime tied to the {@link
///                java.lang.foreign.Arena Arena} that produced it
/// @param context human-readable label naming the on-disk structure this region represents
public record BoundedSegment(MemorySegment seg, String context) {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfInt LE_INT =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /// @return total size of the bounded region in bytes
    public long byteSize() {
        return seg.byteSize();
    }

    /// Returns a sub-region with a fresh context label.
    ///
    /// @param off          start offset in bytes, relative to this region
    /// @param len          slice length in bytes
    /// @param childContext label for the resulting sub-region
    /// @return the bounded sub-region
    /// @throws VortexException if {@code off} or {@code len} is negative, or if
    ///                         {@code off + len > this.byteSize()}
    public BoundedSegment slice(long off, long len, String childContext) {
        checkRange(off, len);
        return new BoundedSegment(seg.asSlice(off, len), childContext);
    }

    /// Bounds-checked single-byte read.
    ///
    /// @param off byte offset
    /// @return the byte at {@code off}
    /// @throws VortexException if {@code off} is negative or {@code >= this.byteSize()}
    public byte getByte(long off) {
        checkRange(off, 1);
        return seg.get(BYTE, off);
    }

    /// Bounds-checked little-endian 32-bit read.
    ///
    /// @param off byte offset of the 4-byte word
    /// @return the int at {@code off}
    /// @throws VortexException if {@code off} is negative or {@code > this.byteSize() - 4}
    public int getIntLE(long off) {
        checkRange(off, 4);
        return seg.get(LE_INT, off);
    }

    /// Bounds-checked little-endian 64-bit read.
    ///
    /// @param off byte offset of the 8-byte word
    /// @return the long at {@code off}
    /// @throws VortexException if {@code off} is negative or {@code > this.byteSize() - 8}
    public long getLongLE(long off) {
        checkRange(off, 8);
        return seg.get(LE_LONG, off);
    }

    private void checkRange(long off, long len) {
        long segSize = seg.byteSize();
        if (off < 0) {
            throw new VortexException("malformed " + context + ": negative offset " + off);
        }
        if (len < 0) {
            throw new VortexException("malformed " + context + ": negative length " + len);
        }
        // Overflow-safe form of `off + len > segSize`. The subtraction can't underflow because
        // len has already been bounded against segSize on the line above (segSize >= 0 always).
        if (len > segSize || off > segSize - len) {
            throw new VortexException("malformed " + context + ": offset+length "
                    + off + "+" + len + " exceeds segment size " + segSize);
        }
    }

    /// Little-endian {@link ByteBuffer} view of the whole bounded region, used by the
    /// FlatBuffer runtime (which performs its own offset validation against the buffer's
    /// capacity).
    ///
    /// @return a {@link ByteBuffer} view in little-endian order
    public ByteBuffer asByteBufferLE() {
        return seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Escape hatch returning the raw {@link MemorySegment} for a downstream parser that
    /// takes its own bounds-checked cursor (currently {@link
    /// io.github.dfa1.vortex.proto.ProtoReader}). The {@code reason} string names the
    /// sub-parser for diagnostic attribution at the call site.
    ///
    /// <p><strong>Audit point.</strong> Every call to this method is a trust transfer
    /// across the bounds-checking boundary. New call sites must justify in review why
    /// the receiver re-validates the bounds itself.
    ///
    /// @param reason short label naming the sub-parser ({@code "proto reader"},
    ///               {@code "flatbuffer root"})
    /// @return the raw memory segment
    public MemorySegment unwrapForSubParser(String reason) {
        return seg;
    }
}
