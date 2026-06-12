package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Footer;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.SegmentSpec;
import io.github.dfa1.vortex.core.array.Array;

import java.io.Closeable;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Common interface for handles to a Vortex file, regardless of storage backend.
///
/// Implementations: [VortexReader] (memory-mapped local file), [VortexHttpReader] (HTTP Range reads).
public interface VortexHandle extends Closeable {

    DType dtype();

    Layout layout();

    Footer footer();

    int version();

    long fileSize();

    /// Typed accessor for the common pattern "read a flat segment by its {@link SegmentSpec}
    /// and decode the encoded array contained therein."
    ///
    /// @param spec     the segment spec to read from
    /// @param dtype    logical type of the decoded array
    /// @param rowCount number of logical rows in the segment
    /// @param arena    allocator for decode output; lifetime matches the caller's chunk epoch
    /// @return the decoded array
    Array decodeFlatSegment(SegmentSpec spec, DType dtype, long rowCount, SegmentAllocator arena);

    /// Returns a read-only view of the bytes backing the given segment spec.
    /// Writes through the returned segment throw {@code UnsupportedOperationException}.
    ///
    /// <p>On memory-mapped handles this is a zero-copy slice of the mapped region.
    /// On HTTP handles this fires a targeted range request.
    ///
    /// @param spec the segment to read
    /// @return a read-only {@link MemorySegment} covering exactly {@code spec.offset()} to
    ///         {@code spec.offset() + spec.length()}
    MemorySegment rawSegment(SegmentSpec spec);

    ScanIterator scan(ScanOptions options);

    /// Returns the {@link ReadRegistry} this handle was opened with.
    ///
    /// <p><strong>Internal escape hatch.</strong> Exposed for tooling
    /// (e.g. the inspector's dictionary preview) that needs to decode an
    /// internal subtree node directly via {@link io.github.dfa1.vortex.encoding.FlatSegmentDecoder}.
    /// Not part of the supported stability contract; signatures may change
    /// without deprecation.
    ///
    /// @return the registry used to resolve encoding ids during scan
    ReadRegistry registry();

    @Override
    void close();
}
