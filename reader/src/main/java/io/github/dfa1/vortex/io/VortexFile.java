package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.core.*;
import io.github.dfa1.vortex.encoding.DecoderRegistry;
import io.github.dfa1.vortex.scan.ScanIterator;
import io.github.dfa1.vortex.scan.ScanOptions;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Handle to an open Vortex file. Memory-maps the file via the FFM API;
 * all Array buffers returned during scan are slices of this MemorySegment.
 *
 * <p>Close this to release the memory-mapped region.
 */
public final class VortexFile implements Closeable {

    static final ValueLayout.OfShort LE_SHORT =
        ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfInt LE_INT =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong LE_LONG =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    static final byte[] MAGIC       = {'V', 'T', 'X', 'F'};
    static final int    TRAILER_SIZE = 8;

    private final Arena           arena;
    private final MemorySegment   fileSegment;
    private final long            fileSize;
    private final int             version;
    private final Footer          footer;
    private final DType           dtype;
    private final Layout          layout;
    private final DecoderRegistry registry;

    private VortexFile(
        Arena arena, MemorySegment fileSegment, long fileSize,
        int version, Footer footer, DType dtype, Layout layout,
        DecoderRegistry registry
    ) {
        this.arena       = arena;
        this.fileSegment = fileSegment;
        this.fileSize    = fileSize;
        this.version     = version;
        this.footer      = footer;
        this.dtype       = dtype;
        this.layout      = layout;
        this.registry    = registry;
    }

    /**
     * Open a Vortex file. Memory-maps the entire file; all subsequent reads
     * are zero-copy slices. Call {@link #close()} when done.
     */
    public static VortexFile open(Path path) throws IOException {
        return open(path, DecoderRegistry.loadAll());
    }

    public static VortexFile open(Path path, DecoderRegistry registry) throws IOException {
        var arena = Arena.ofConfined();
        try {
            var channel = FileChannel.open(path, StandardOpenOption.READ);
            long size = channel.size();
            if (size < TRAILER_SIZE) {
                throw new IOException("vortex: file too small (" + size + " bytes)");
            }
            var segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            channel.close();
            return parse(segment, size, arena, registry);
        } catch (Exception e) {
            arena.close();
            if (e instanceof IOException ioe) throw ioe;
            throw new IOException(e);
        }
    }

    private static VortexFile parse(
        MemorySegment seg, long size, Arena arena, DecoderRegistry registry
    ) throws IOException {
        // 8-byte trailer: version(u16 LE) | postscriptLen(u16 LE) | magic(4)
        var trailer = seg.asSlice(size - TRAILER_SIZE, TRAILER_SIZE);

        int version       = Short.toUnsignedInt(trailer.get(LE_SHORT, 0));
        int postscriptLen = Short.toUnsignedInt(trailer.get(LE_SHORT, 2));

        byte m0 = trailer.get(ValueLayout.JAVA_BYTE, 4);
        byte m1 = trailer.get(ValueLayout.JAVA_BYTE, 5);
        byte m2 = trailer.get(ValueLayout.JAVA_BYTE, 6);
        byte m3 = trailer.get(ValueLayout.JAVA_BYTE, 7);
        if (m0 != MAGIC[0] || m1 != MAGIC[1] || m2 != MAGIC[2] || m3 != MAGIC[3]) {
            throw new IOException(
                "vortex: invalid magic bytes [%02x %02x %02x %02x]".formatted(m0, m1, m2, m3));
        }

        long postscriptOffset = size - TRAILER_SIZE - postscriptLen;
        var  postscriptBytes  = seg.asSlice(postscriptOffset, postscriptLen)
                                   .toArray(ValueLayout.JAVA_BYTE);

        var parsed = PostscriptParser.parse(postscriptBytes, seg);

        return new VortexFile(
            arena, seg, size, version,
            parsed.footer(), parsed.dtype(), parsed.layout(),
            registry
        );
    }

    public DType           dtype()    { return dtype; }
    public Layout          layout()   { return layout; }
    public Footer          footer()   { return footer; }
    public int             version()  { return version; }
    public long            fileSize() { return fileSize; }
    public DecoderRegistry registry() { return registry; }

    public ScanIterator scan(ScanOptions options) { return new ScanIterator(this, options); }

    /** Zero-copy slice of the memory-mapped file. */
    public MemorySegment slice(long offset, long length) {
        return fileSegment.asSlice(offset, length);
    }

    @Override
    public void close() {
        arena.close();
    }
}
