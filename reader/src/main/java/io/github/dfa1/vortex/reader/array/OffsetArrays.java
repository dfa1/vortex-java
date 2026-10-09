package io.github.dfa1.vortex.reader.array;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

/// Package-private helper shared by the numeric `OffsetXxxArray` slices.
final class OffsetArrays {

    private OffsetArrays() {
    }

    /// The window `[offset, offset + length)` of a flat inner buffer, zero-copy: a scan window's
    /// slice of a canonicalized shared flat materializes to a sub-range of it instead of copying it
    /// row by row through `getX`. Empty when the inner array has no segment, or one shorter than
    /// the window (a broadcast buffer), so the caller keeps its per-row path.
    ///
    /// @param inner  the sliced array
    /// @param offset first inner element of the window
    /// @param length window length in elements
    /// @param width  element width in bytes
    /// @return the window as a read-only slice of the inner buffer, if the buffer covers it
    static Optional<MemorySegment> window(Array inner, long offset, long length, int width) {
        return inner.segmentIfPresent()
                .filter(seg -> seg.byteSize() >= (offset + length) * width)
                .map(seg -> seg.asSlice(offset * width, length * width).asReadOnly());
    }
}
