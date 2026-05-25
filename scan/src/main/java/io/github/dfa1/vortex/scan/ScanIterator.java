package io.github.dfa1.vortex.scan;

import io.github.dfa1.vortex.io.VortexFile;

import java.io.IOException;

/**
 * Iterates over decoded chunks from a {@link VortexFile}.
 *
 * <p>Usage:
 * <pre>{@code
 * try (var iter = file.scan(ScanOptions.all())) {
 *     while (iter.hasNext()) {
 *         ScanResult chunk = iter.next();
 *     }
 * }
 * }</pre>
 */
public final class ScanIterator implements AutoCloseable {

    private final VortexFile  file;
    private final ScanOptions options;
    private       int         chunkIndex;
    private       ScanResult  current;

    ScanIterator(VortexFile file, ScanOptions options) {
        this.file    = file;
        this.options = options;
    }

    public boolean hasNext() throws IOException {
        // TODO: traverse layout tree (Struct → Zoned → Chunked → Flat),
        //       apply zone-map pruning from ScanOptions.rowFilter,
        //       respect column projection and limit.
        throw new UnsupportedOperationException("ScanIterator not yet implemented");
    }

    public ScanResult next() throws IOException {
        if (current == null) throw new IllegalStateException("call hasNext() first");
        return current;
    }

    @Override
    public void close() {}
}
