package io.github.dfa1.vortex.core;

import java.lang.foreign.MemorySegment;

/// Bounds-checked wrappers for {@link MemorySegment} slicing on untrusted input.
///
/// <p>Application code in {@code io}, {@code scan}, and {@code encoding} should prefer
/// {@link BoundedSegment}, which encapsulates a segment + context label and makes the
/// safe-slice operation the only available API on the type. This class is the underlying
/// implementation: {@code BoundedSegment.slice} delegates to {@link #slice}, and
/// {@code BoundedSegment}'s primitive readers delegate to {@link #checkRange}.
///
/// <p>Direct {@code MemorySegments.slice} use is reserved for the few places that build a
/// {@code BoundedSegment} in the first place (the mmap boundary in {@code VortexReader.parse})
/// or that need a bounded {@link MemorySegment} without producing a {@code BoundedSegment}.
/// In both cases the contract is the same: malformed input throws {@link VortexException},
/// never {@link IndexOutOfBoundsException}, {@link IllegalArgumentException}, or any other
/// unchecked JDK exception.
public final class MemorySegments {

    private MemorySegments() {
    }

    /// Returns a slice of {@code seg} starting at {@code off} for {@code len} bytes,
    /// rejecting out-of-range or overflow-prone input with a {@link VortexException}
    /// labelled by {@code context}.
    ///
    /// @param seg     backing segment
    /// @param off     start offset in bytes; must be {@code >= 0} and {@code <= seg.byteSize() - len}
    /// @param len     slice length in bytes; must be {@code >= 0} and {@code <= seg.byteSize() - off}
    /// @param context short label used in the exception message (e.g. {@code "footer blob"},
    ///                {@code "segment spec data"}) so malformed-input errors point at the
    ///                specific on-disk structure rather than a generic offset
    /// @return the bounds-checked slice
    /// @throws VortexException if {@code off} or {@code len} is negative, or if
    ///                         {@code off + len > seg.byteSize()}
    public static MemorySegment slice(MemorySegment seg, long off, long len, String context) {
        checkRange(seg, off, len, context);
        return seg.asSlice(off, len);
    }

    /// Bounds-check {@code off} and {@code len} against {@code seg} without producing a slice.
    /// Used by {@link BoundedSegment}'s primitive readers, which need bounds-checking before
    /// a {@link MemorySegment#get} call but do not need to materialise a sub-segment.
    ///
    /// @param seg     backing segment
    /// @param off     start offset
    /// @param len     range length
    /// @param context label used in the {@link VortexException} message
    /// @throws VortexException if {@code off} or {@code len} is negative, or if
    ///                         {@code off + len > seg.byteSize()}
    public static void checkRange(MemorySegment seg, long off, long len, String context) {
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
}
