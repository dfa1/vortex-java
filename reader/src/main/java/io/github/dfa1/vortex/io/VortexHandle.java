package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Footer;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.scan.ScanIterator;
import io.github.dfa1.vortex.scan.ScanOptions;

import java.io.Closeable;
import java.lang.foreign.MemorySegment;

/// Common interface for handles to a Vortex file, regardless of storage backend.
///
/// Implementations: [VortexReader] (memory-mapped local file), [VortexHttpReader] (HTTP Range reads).
public interface VortexHandle extends Closeable {

    DType dtype();

    Layout layout();

    Footer footer();

    int version();

    long fileSize();

    /// Returns a read-only view of bytes `[offset, offset+length)` within the file.
    /// Writes through the returned segment throw `UnsupportedOperationException`.
    ///
    /// <p><strong>Internal escape hatch.</strong> This method is on the public
    /// {@link VortexHandle} interface only because {@link io.github.dfa1.vortex.scan.ScanIterator}
    /// and the inspector module's {@code VortexInspector} live in sibling packages and need
    /// cross-package access to the raw backing segment. It is not part of the supported stability contract; signatures and
    /// semantics may change without a deprecation cycle. Application code should rely on
    /// {@link #scan(ScanOptions)} and the typed array accessors instead.
    ///
    /// @param offset the start offset in bytes
    /// @param length the number of bytes to expose
    /// @return a read-only [MemorySegment] view of the requested range
    /// @deprecated marked for removal once the reader-internal packages consolidate (see
    /// {@code TODO.md}); kept here as an interim escape hatch for vortex-internal callers.
    @Deprecated(since = "0.4.0", forRemoval = true)
    MemorySegment slice(long offset, long length);

    ScanIterator scan(ScanOptions options);

    @Override
    void close();
}
