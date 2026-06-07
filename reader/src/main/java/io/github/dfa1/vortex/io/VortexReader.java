package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Footer;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.SegmentSpec;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.VortexFormat;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.scan.ScanIterator;
import io.github.dfa1.vortex.scan.ScanOptions;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Handle to an open Vortex file. Memory-maps the file via the FFM API;
/// all Array buffers returned during scan are slices of this `MemorySegment`.
///
/// Close this to release the memory-mapped region.
public final class VortexReader implements VortexHandle {

    static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfInt LE_INT =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final Arena arena;
    private final MemorySegment fileSegment;
    private final long fileSize;
    private final int version;
    private final Footer footer;
    private final DType dtype;
    private final Layout layout;
    private final EncodingRegistry registry;

    private VortexReader(
            Arena arena, MemorySegment fileSegment, long fileSize,
            int version, Footer footer, DType dtype, Layout layout,
            EncodingRegistry registry
    ) {
        this.arena = arena;
        this.fileSegment = fileSegment;
        this.fileSize = fileSize;
        this.version = version;
        this.footer = footer;
        this.dtype = dtype;
        this.layout = layout;
        this.registry = registry;
    }

    /// Open a Vortex file. Memory-maps the entire file; all subsequent reads
    /// are zero-copy slices. Call [#close()] when done.
    public static VortexReader open(Path path) throws IOException {
        return open(path, EncodingRegistry.loadAll());
    }

    public static VortexReader open(Path path, EncodingRegistry registry) throws IOException {
        Arena arena = Arena.ofConfined();
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < VortexFormat.TRAILER_SIZE) {
                throw new VortexException("file too small (" + size + " bytes)");
            }
            // The channel is no longer needed after map(): the Arena owns the mapping's
            // lifetime. try-with-resources closes the file descriptor while all Array
            // buffers remain valid zero-copy slices until arena.close() is called.
            var segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            return parse(segment, size, arena, registry);
        } catch (Exception e) {
            arena.close();
            throw e;
        }
    }

    private static VortexReader parse(
            MemorySegment seg, long size, Arena arena, EncodingRegistry registry
    ) {
        // 8-byte trailer: version(u16 LE) | postscriptLen(u16 LE) | magic(4)
        var trailer = seg.asSlice(size - VortexFormat.TRAILER_SIZE, VortexFormat.TRAILER_SIZE);

        int version = Short.toUnsignedInt(trailer.get(LE_SHORT, 0));
        int postscriptLen = Short.toUnsignedInt(trailer.get(LE_SHORT, 2));

        MemorySegment magicSlice = trailer.asSlice(4, VortexFormat.MAGIC_SIZE);
        if (magicSlice.mismatch(VortexFormat.MAGIC) != -1) {
            throw new VortexException(
                    "invalid magic bytes [%02x %02x %02x %02x]".formatted(
                            magicSlice.get(ValueLayout.JAVA_BYTE, 0),
                            magicSlice.get(ValueLayout.JAVA_BYTE, 1),
                            magicSlice.get(ValueLayout.JAVA_BYTE, 2),
                            magicSlice.get(ValueLayout.JAVA_BYTE, 3)));
        }

        if (version != VortexFormat.VERSION) {
            throw new VortexException(
                    "unsupported file version=" + version
                            + " (this reader supports version " + VortexFormat.VERSION + ")");
        }
        if (postscriptLen == 0) {
            throw new VortexException("invalid postscript: length is zero");
        }
        long bodyBytes = size - VortexFormat.TRAILER_SIZE;
        if (postscriptLen > bodyBytes) {
            throw new VortexException(
                    "invalid postscript: length=" + postscriptLen
                            + " exceeds file body size=" + bodyBytes);
        }

        long postscriptOffset = bodyBytes - postscriptLen;
        var postscriptBuf = seg.asSlice(postscriptOffset, postscriptLen)
                                    .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);

        PostscriptParser.ParsedFile parsed;
        try {
            parsed = PostscriptParser.parse(postscriptBuf, seg, size);
        } catch (VortexException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new VortexException("malformed postscript", e);
        }

        return new VortexReader(
                arena, seg, size, version,
                parsed.footer(), parsed.dtype(), parsed.layout(),
                registry
        );
    }

    private static void collectFlats(Layout layout, List<Layout> out) {
        if (layout.isFlat() || layout.isDict()) {
            out.add(layout);
        } else if (layout.isZoned() && !layout.children().isEmpty()) {
            collectFlats(layout.children().getFirst(), out);
        } else if (layout.isChunked()) {
            int start = (layout.metadata() != null
                                 && layout.metadata().hasRemaining()
                                 && layout.metadata().get(0) == 1) ? 1 : 0;
            for (int i = start; i < layout.children().size(); i++) {
                collectFlats(layout.children().get(i), out);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object minOf(Object a, Object b) {
        return ((Comparable<Object>) a).compareTo(b) <= 0 ? a : b;
    }

    @SuppressWarnings("unchecked")
    private static Object maxOf(Object a, Object b) {
        return ((Comparable<Object>) a).compareTo(b) >= 0 ? a : b;
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public Layout layout() {
        return layout;
    }

    @Override
    public Footer footer() {
        return footer;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public long fileSize() {
        return fileSize;
    }

    @Override
    public EncodingRegistry registry() {
        return registry;
    }

    @Override
    public ScanIterator scan(ScanOptions options) {
        return new ScanIterator(this, options);
    }

    /// Aggregated per-column statistics (global min/max across all chunks).
    /// Returns an empty map if the root layout is not a struct.
    /// Columns with no embedded stats return [ArrayStats#empty()].
    public Map<String, ArrayStats> columnStats() {
        if (!layout.isStruct() || !(dtype instanceof DType.Struct schema)) {
            return Map.of();
        }
        List<String> names = schema.fieldNames();
        List<Layout> colLayouts = layout.children();
        Map<String, ArrayStats> result = new LinkedHashMap<>();
        for (int i = 0; i < names.size() && i < colLayouts.size(); i++) {
            List<Layout> flats = new ArrayList<>();
            collectFlats(colLayouts.get(i), flats);
            result.put(names.get(i), aggregateStats(flats));
        }
        return Map.copyOf(result);
    }

    private ArrayStats aggregateStats(List<Layout> flats) {
        Object globalMin = null;
        Object globalMax = null;
        for (Layout flat : flats) {
            ArrayStats s = readFlatStats(flat);
            if (s.min() != null) {
                globalMin = globalMin == null ? s.min() : minOf(globalMin, s.min());
            }
            if (s.max() != null) {
                globalMax = globalMax == null ? s.max() : maxOf(globalMax, s.max());
            }
        }
        if (globalMin == null && globalMax == null) {
            return ArrayStats.empty();
        }
        return new ArrayStats(globalMin, globalMax, null, null, null, null);
    }

    private ArrayStats readFlatStats(Layout flat) {
        if (flat.segments().isEmpty()) {
            return ArrayStats.empty();
        }
        int segIdx = flat.segments().getFirst();
        if (segIdx < 0 || segIdx >= footer.segmentSpecs().size()) {
            return ArrayStats.empty();
        }
        SegmentSpec spec = footer.segmentSpecs().get(segIdx);
        long segLen = spec.length();
        // Need at least 4 bytes for the trailing little-endian fbLen.
        if (segLen < 4) {
            return ArrayStats.empty();
        }
        MemorySegment seg = fileSegment.asSlice(spec.offset(), segLen);
        int fbLen = seg.get(LE_INT, segLen - 4);
        // Reject negative fbLen (signed int from untrusted bytes) or any value that would push
        // fbStart below 0 → asSlice(negative, ...) throws IndexOutOfBoundsException without this guard.
        if (fbLen < 0 || fbLen > segLen - 4) {
            return ArrayStats.empty();
        }
        long fbStart = segLen - 4L - fbLen;
        var fbBuf = seg.asSlice(fbStart, fbLen).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        var fbArray = io.github.dfa1.vortex.fbs.Array.getRootAsArray(fbBuf);
        var root = fbArray.root();
        if (root == null) {
            return ArrayStats.empty();
        }
        return ArrayStats.fromFbs(root.stats());
    }

    /// Zero-copy read-only slice of the memory-mapped file.
    @Override
    public MemorySegment slice(long offset, long length) {
        return fileSegment.asSlice(offset, length).asReadOnly();
    }

    @Override
    public void close() {
        arena.close();
    }
}
