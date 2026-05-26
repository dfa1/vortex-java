package io.github.dfa1.vortex.scan;

import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.SegmentSpec;
import io.github.dfa1.vortex.encoding.Array;
import io.github.dfa1.vortex.encoding.ArrayNode;
import io.github.dfa1.vortex.encoding.ArrayStats;
import io.github.dfa1.vortex.encoding.DecodeContext;
import io.github.dfa1.vortex.fbs.Buffer;
import io.github.dfa1.vortex.io.VortexFile;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Iterates over decoded chunks from a [VortexFile].
///
/// Usage:
/// ```java
/// try (var iter = file.scan(ScanOptions.all())) {
///     while (iter.hasNext()) {
///         ScanResult chunk = iter.next();
///     }
/// }
/// ```
public final class ScanIterator implements AutoCloseable {

    private final VortexFile  file;
    private final ScanOptions options;

    private List<ChunkSpec>    chunks;
    private Map<String, DType> columnDtypes;
    private int                chunkIndex;
    private long               rowsReturned;
    private ScanResult         current;

    public ScanIterator(VortexFile file, ScanOptions options) {
        this.file    = file;
        this.options = options;
    }

    public boolean hasNext() throws IOException {
        if (chunks == null) {
            initialize();
        }
        if (rowsReturned >= options.limit()) {
            return false;
        }

        while (chunkIndex < chunks.size()) {
            ChunkSpec chunk = chunks.get(chunkIndex++);
            if (options.hasFilter() && canPruneChunk(chunk, options.rowFilter())) {
                continue;
            }

            var columns = new LinkedHashMap<String, Array>(chunk.columnFlats().size());
            for (var entry : chunk.columnFlats().entrySet()) {
                DType colDtype = columnDtypes.get(entry.getKey());
                columns.put(entry.getKey(), decodeFlat(entry.getValue(), colDtype));
            }

            current = new ScanResult(chunk.rowCount(), Map.copyOf(columns));
            rowsReturned += chunk.rowCount();
            return true;
        }
        return false;
    }

    public ScanResult next() throws IOException {
        if (current == null) {
            throw new IllegalStateException("call hasNext() first");
        }
        return current;
    }

    @Override
    public void close() {}

    // ── Layout tree traversal ─────────────────────────────────────────────────

    private void initialize() {
        Layout rootLayout = file.layout();
        DType  rootDtype  = file.dtype();

        var columnFlats = new LinkedHashMap<String, List<Layout>>();
        columnDtypes    = new LinkedHashMap<>();

        if (rootLayout.isStruct() && rootDtype instanceof DType.Struct structDtype) {
            List<String> projection = options.columns();
            for (int i = 0; i < rootLayout.children().size(); i++) {
                String colName  = structDtype.fieldNames().get(i);
                DType  colDtype = structDtype.fieldTypes().get(i);
                if (!projection.isEmpty() && !projection.contains(colName)) {
                    continue;
                }
                var flats = new ArrayList<Layout>();
                collectFlats(rootLayout.children().get(i), flats);
                columnFlats.put(colName, flats);
                columnDtypes.put(colName, colDtype);
            }
        } else {
            var flats = new ArrayList<Layout>();
            collectFlats(rootLayout, flats);
            columnFlats.put("_col", flats);
            columnDtypes.put("_col", rootDtype);
        }

        chunks = buildChunks(columnFlats);
    }

    private static void collectFlats(Layout layout, List<Layout> out) {
        if (layout.isFlat()) {
            out.add(layout);
        } else if (layout.isZoned()) {
            // vortex.stats wraps one child (the data layout) — pass through for data
            if (!layout.children().isEmpty()) {
                collectFlats(layout.children().get(0), out);
            }
        } else if (layout.isChunked()) {
            // metadata[0] == 1 means children[0] is the per-chunk stats layout; skip it
            int start = (layout.metadata() != null
                         && layout.metadata().hasRemaining()
                         && layout.metadata().get(0) == 1) ? 1 : 0;
            for (int i = start; i < layout.children().size(); i++) {
                collectFlats(layout.children().get(i), out);
            }
        }
    }

    private static List<ChunkSpec> buildChunks(Map<String, List<Layout>> columnFlats) {
        if (columnFlats.isEmpty()) {
            return List.of();
        }

        int numChunks = columnFlats.values().iterator().next().size();
        var result    = new ArrayList<ChunkSpec>(numChunks);
        for (int i = 0; i < numChunks; i++) {
            final int ci = i;
            var cols = new LinkedHashMap<String, Layout>();
            columnFlats.forEach((col, flats) -> cols.put(col, flats.get(ci)));
            long rowCount = cols.values().iterator().next().rowCount();
            result.add(new ChunkSpec(rowCount, Map.copyOf(cols)));
        }
        return List.copyOf(result);
    }

    // ── Flat segment decoding ─────────────────────────────────────────────────

    private Array decodeFlat(Layout flat, DType dtype) throws IOException {
        if (flat.segments().isEmpty()) {
            throw new IOException("vortex: Flat layout has no segments");
        }

        int         segIdx = flat.segments().get(0);
        SegmentSpec spec   = file.footer().segmentSpecs().get(segIdx);
        int         segLen = spec.length();
        MemorySegment seg  = file.slice(spec.offset(), segLen);
        ByteBuffer bb      = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);

        // Segment format: [buffer data...] [FlatBuffer Array] [4-byte LE u32 = FlatBuffer size]
        int fbLen   = bb.getInt(segLen - 4);
        int fbStart = segLen - 4 - fbLen;
        ByteBuffer fbBuf = bb.slice(fbStart, fbLen).order(ByteOrder.LITTLE_ENDIAN);
        var fbArray = io.github.dfa1.vortex.fbs.Array.getRootAsArray(fbBuf);

        // Buffer data is at the beginning of the segment, before the FlatBuffer
        int numBuffers       = fbArray.buffersLength();
        MemorySegment[] bufs = new MemorySegment[numBuffers];
        long dataOffset      = 0;
        for (int i = 0; i < numBuffers; i++) {
            Buffer bufDesc = fbArray.buffers(i);
            dataOffset += bufDesc.padding();
            bufs[i]     = seg.asSlice(dataOffset, bufDesc.length());
            dataOffset += bufDesc.length();
        }

        ArrayNode rootNode = convertArrayNode(fbArray.root(), file.footer().arraySpecs());
        var ctx = new DecodeContext(rootNode, dtype, flat.rowCount(), bufs, file.registry());
        return file.registry().decode(ctx);
    }

    private static ArrayNode convertArrayNode(
        io.github.dfa1.vortex.fbs.ArrayNode fbs,
        List<String> arraySpecs
    ) {
        String encodingId = arraySpecs.get(fbs.encoding());

        ArrayNode[] children = new ArrayNode[fbs.childrenLength()];
        for (int i = 0; i < children.length; i++) {
            children[i] = convertArrayNode(fbs.children(i), arraySpecs);
        }

        int[] bufferIndices = new int[fbs.buffersLength()];
        for (int i = 0; i < bufferIndices.length; i++) {
            bufferIndices[i] = fbs.buffers(i);
        }

        // metadataAsByteBuffer() returns a duplicate with position=vectorStart; slice to normalize position to 0
        ByteBuffer rawMeta = fbs.metadataAsByteBuffer();
        ByteBuffer meta = (rawMeta != null) ? rawMeta.slice() : null;
        return new ArrayNode(encodingId, meta, children, bufferIndices, ArrayStats.empty());
    }

    // ── Zone-map pruning ──────────────────────────────────────────────────────

    private boolean canPruneChunk(ChunkSpec chunk, RowFilter filter) throws IOException {
        return switch (filter) {
            case RowFilter.And(var filters) -> {
                for (RowFilter f : filters) {
                    if (canPruneChunk(chunk, f)) {
                        yield true;
                    }
                }
                yield false;
            }
            case RowFilter.Gte(var col, var val) -> {
                Layout flat = chunk.columnFlats().get(col);
                if (flat == null) {
                    yield false;
                }
                Object max = readFlatStats(flat).max();
                yield max != null && compareValues(max, val) < 0;
            }
            case RowFilter.Lte(var col, var val) -> {
                Layout flat = chunk.columnFlats().get(col);
                if (flat == null) {
                    yield false;
                }
                Object min = readFlatStats(flat).min();
                yield min != null && compareValues(min, val) > 0;
            }
            case RowFilter.Eq(var col, var val) -> {
                Layout flat = chunk.columnFlats().get(col);
                if (flat == null) {
                    yield false;
                }
                ArrayStats stats = readFlatStats(flat);
                Object min = stats.min();
                Object max = stats.max();
                if (min == null || max == null) {
                    yield false;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Comparable<Object> cv = (Comparable<Object>) val;
                    yield cv.compareTo(min) < 0 || cv.compareTo(max) > 0;
                } catch (ClassCastException e) {
                    yield false;
                }
            }
        };
    }

    private ArrayStats readFlatStats(Layout flat) throws IOException {
        if (flat.segments().isEmpty()) {
            return ArrayStats.empty();
        }
        int         segIdx = flat.segments().get(0);
        SegmentSpec spec   = file.footer().segmentSpecs().get(segIdx);
        int         segLen = spec.length();
        MemorySegment seg  = file.slice(spec.offset(), segLen);
        ByteBuffer bb      = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);

        int fbLen   = bb.getInt(segLen - 4);
        int fbStart = segLen - 4 - fbLen;
        ByteBuffer fbBuf = bb.slice(fbStart, fbLen).order(ByteOrder.LITTLE_ENDIAN);
        var fbArray      = io.github.dfa1.vortex.fbs.Array.getRootAsArray(fbBuf);

        io.github.dfa1.vortex.fbs.ArrayNode root = fbArray.root();
        if (root == null) {
            return ArrayStats.empty();
        }
        io.github.dfa1.vortex.fbs.ArrayStats fbStats = root.stats();
        if (fbStats == null) {
            return ArrayStats.empty();
        }

        Object min = decodeScalarValue(fbStats.minAsByteBuffer());
        Object max = decodeScalarValue(fbStats.maxAsByteBuffer());
        return new ArrayStats(min, max, null, null, null, null);
    }

    private static Object decodeScalarValue(ByteBuffer bytes) {
        if (bytes == null || !bytes.hasRemaining()) {
            return null;
        }
        try {
            byte[] arr = new byte[bytes.remaining()];
            bytes.duplicate().get(arr);
            ScalarProtos.ScalarValue sv = ScalarProtos.ScalarValue.parseFrom(arr);
            return switch (sv.getKindCase()) {
                case INT64_VALUE  -> sv.getInt64Value();
                case UINT64_VALUE -> sv.getUint64Value();
                case F32_VALUE    -> sv.getF32Value();
                case F64_VALUE    -> sv.getF64Value();
                case BOOL_VALUE   -> sv.getBoolValue();
                default           -> null;
            };
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static int compareValues(Object a, Object b) {
        try {
            return ((Comparable<Object>) a).compareTo(b);
        } catch (ClassCastException e) {
            return 0;
        }
    }

    // ── Internal record ───────────────────────────────────────────────────────

    private record ChunkSpec(long rowCount, Map<String, Layout> columnFlats) {}
}
