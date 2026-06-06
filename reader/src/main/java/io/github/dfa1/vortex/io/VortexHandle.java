package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Footer;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
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

    EncodingRegistry registry();

    /// Returns a view of bytes `[offset, offset+length)` within the file.
    MemorySegment slice(long offset, long length);

    ScanIterator scan(ScanOptions options);

    @Override
    void close();
}
