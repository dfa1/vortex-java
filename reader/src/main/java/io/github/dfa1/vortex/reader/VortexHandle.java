package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.reader.layout.LayoutRegistry;

import java.io.Closeable;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Collection;

/// Common interface for handles to a Vortex file, regardless of storage backend.
///
/// Implementations: [VortexReader] (memory-mapped local file), [VortexHttpReader] (HTTP Range reads).
public interface VortexHandle extends Closeable {

    /// Returns the file's root dtype.
    ///
    /// @return the root dtype, a [DType.Struct] for files written by a `VortexWriter`
    DType dtype();

    /// Returns the root of the file's layout tree.
    ///
    /// @return the root layout
    Layout layout();

    /// Returns the parsed file footer: segment map, array specs and layout specs.
    ///
    /// @return the footer
    Footer footer();

    /// Returns the format version from the file's trailer.
    ///
    /// @return the trailer version
    int version();

    /// Returns the size of the file.
    ///
    /// @return the file size in bytes
    long fileSize();

    /// Typed accessor for the common pattern "read a flat segment by its [SegmentSpec]
    /// and decode the encoded array contained therein."
    ///
    /// @param spec     the segment spec to read from
    /// @param dtype    logical type of the decoded array
    /// @param rowCount number of logical rows in the segment
    /// @param arena    allocator for decode output; lifetime matches the caller's chunk epoch
    /// @return the decoded array
    Array decodeSegment(SegmentSpec spec, DType dtype, long rowCount, SegmentAllocator arena);

    /// Returns a read-only view of the bytes backing the given segment spec.
    /// Writes through the returned segment throw `UnsupportedOperationException`.
    ///
    /// On memory-mapped handles this is a zero-copy slice of the mapped region.
    /// On HTTP handles this fires a targeted range request.
    ///
    /// @param spec the segment to read
    /// @return a read-only [MemorySegment] covering exactly `spec.offset()` to
    ///         `spec.offset() + spec.length()`
    MemorySegment rawSegment(SegmentSpec spec);

    /// Returns the end of the given segment: at least its array-node FlatBuffer and the 4-byte
    /// length that follows it, which is all the inspector reads to name an encoding and its stats.
    /// May be the whole segment. On an HTTP handle it fetches a few KiB instead of the segment.
    ///
    /// @param spec the segment to read the end of
    /// @return a read-only [MemorySegment] ending at the same byte as the segment
    default MemorySegment segmentTail(SegmentSpec spec) {
        return rawSegment(spec);
    }

    /// Hints that `specs` are about to be read with [#segmentTail(SegmentSpec)], so a remote
    /// handle can fetch them concurrently. Does nothing on a memory-mapped handle. Call it from
    /// the thread that reads the segments, the one that opened the handle.
    ///
    /// @param specs the segments whose ends are about to be read
    default void prefetchTails(Collection<SegmentSpec> specs) {
    }

    /// Hints that `specs` are about to be read with [#rawSegment(SegmentSpec)], so a remote handle
    /// can fetch them concurrently instead of one round trip each. Does nothing on a
    /// memory-mapped handle, where a read is already free. Call it from the thread that reads the
    /// segments, the one that opened the handle.
    ///
    /// @param specs the segments about to be read
    default void prefetch(Collection<SegmentSpec> specs) {
    }

    /// Starts a scan of the file, yielding one [Chunk] at a time. Close the iterator when done.
    ///
    /// @param options column projection, zone-map filter and row limit
    /// @return an iterator over the file's chunks
    ScanIterator scan(ScanOptions options);

    /// Returns the [ReadRegistry] this handle was opened with.
    ///
    /// **Internal escape hatch.** Exposed for tooling
    /// (e.g. the inspector's dictionary preview) that needs to resolve encoding
    /// decoders for an internal subtree node.
    /// Not part of the supported stability contract; signatures may change
    /// without deprecation.
    ///
    /// @return the registry used to resolve encoding ids during scan
    ReadRegistry registry();

    /// Returns the [LayoutRegistry] this handle was opened with, dispatching full-column layout
    /// subtree decode. Defaults to [LayoutRegistry#defaults()] unless a custom registry was
    /// supplied at open time.
    ///
    /// @return the registry used to decode layout nodes during scan
    LayoutRegistry layoutRegistry();

    @Override
    void close();
}
