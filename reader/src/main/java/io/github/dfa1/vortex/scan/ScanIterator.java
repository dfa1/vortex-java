package io.github.dfa1.vortex.scan;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.SegmentSpec;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.EmptyArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.NullArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.StructArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.encoding.FlatSegmentDecoder;
import io.github.dfa1.vortex.io.VortexHandle;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Iterates over decoded chunks from a [VortexReader].
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

    private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final VortexHandle file;
    private final EncodingRegistry registry;
    private final ScanOptions options;
    private Arena chunkArena;

    private List<ChunkSpec> chunks;
    private List<String> projectedNames;
    private List<DType> projectedDtypes;
    private int chunkIndex;
    private long rowsReturned;
    private ScanResult current;

    public ScanIterator(VortexHandle file, EncodingRegistry registry, ScanOptions options) {
        this.file = file;
        this.registry = registry;
        this.options = options;
    }

    private static void collectFlats(Layout layout, List<Layout> out) {
        if (layout.isFlat()) {
            out.add(layout);
        } else if (layout.isDict()) {
            // Dict layout is a leaf chunk — decoded as a unit (values + codes).
            out.add(layout);
        } else if (layout.isZoned()) {
            // vortex.stats wraps one child (the data layout) — pass through for data
            if (!layout.children().isEmpty()) {
                collectFlats(layout.children().getFirst(), out);
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
        String[] colNames = columnFlats.keySet().toArray(String[]::new);
        int numCols = colNames.length;
        int numChunks = columnFlats.values().iterator().next().size();
        var result = new ArrayList<ChunkSpec>(numChunks);
        for (int i = 0; i < numChunks; i++) {
            Layout[] layouts = new Layout[numCols];
            for (int j = 0; j < numCols; j++) {
                layouts[j] = columnFlats.get(colNames[j]).get(i);
            }
            result.add(new ChunkSpec(layouts[0].rowCount(), colNames, layouts));
        }
        return List.copyOf(result);
    }

    // ── Layout tree traversal ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static int compareValues(Object a, Object b) {
        try {
            return ((Comparable<Object>) a).compareTo(b);
        } catch (ClassCastException e) {
            return 0;
        }
    }

    private static Map<String, Array> expandStruct(StructArray sa) {
        DType.Struct sd = (DType.Struct) sa.dtype();
        List<String> names = sd.fieldNames();
        int n = names.size();
        var map = new LinkedHashMap<String, Array>(n);
        for (int i = 0; i < n; i++) {
            map.put(names.get(i), sa.field(i));
        }
        return Map.copyOf(map);
    }

    private static Array expandDictPrimitive(
            MemorySegment valBuf, MemorySegment codesBuf,
            PType codesPType, DType.Primitive dtype,
            long n, SegmentAllocator arena
    ) {
        PType ptype = dtype.ptype();
        int elemBytes = ptype.byteSize();
        MemorySegment out = arena.allocate(n * elemBytes, elemBytes);
        for (long i = 0; i < n; i++) {
            long code = readUnsigned(codesBuf, i, codesPType);
            MemorySegment.copy(valBuf, code * elemBytes, out, i * elemBytes, elemBytes);
        }
        return switch (ptype) {
            case I32, U32 -> new IntArray(dtype, n, out.asReadOnly());
            case I64, U64 -> new LongArray(dtype, n, out.asReadOnly());
            case F64 -> new DoubleArray(dtype, n, out.asReadOnly());
            case F32 -> new FloatArray(dtype, n, out.asReadOnly());
            case I16, U16 -> new ShortArray(dtype, n, out.asReadOnly());
            case I8, U8 -> new ByteArray(dtype, n, out.asReadOnly());
            default -> throw new VortexException(EncodingId.VORTEX_DICT,
                    "layout: unsupported ptype for dict expansion: " + ptype);
        };
    }

    // ── Column map builder ────────────────────────────────────────────────────

    private static Array expandDictStrings(
            VarBinArray values, MemorySegment codesSegs,
            PType codesPType, DType dtype,
            long n, SegmentAllocator arena
    ) {
        MemorySegment valBytes = values.bytesSegment();
        MemorySegment valOffsets = values.offsetsSegment();
        PType valOffPType = values.offsetsPtype();

        // First pass: total output byte length
        long totalBytes = 0L;
        for (long i = 0; i < n; i++) {
            long code = readUnsigned(codesSegs, i, codesPType);
            long start = readUnsigned(valOffsets, code, valOffPType);
            long end = readUnsigned(valOffsets, code + 1, valOffPType);
            totalBytes += end - start;
        }

        MemorySegment outBytes = arena.allocate(totalBytes > 0 ? totalBytes : 1);
        MemorySegment outOffsets = arena.allocate((n + 1) * 4L, 4);
        outOffsets.setAtIndex(LE_INT, 0, 0);

        long bytePos = 0L;
        for (long i = 0; i < n; i++) {
            long code = readUnsigned(codesSegs, i, codesPType);
            long start = readUnsigned(valOffsets, code, valOffPType);
            long end = readUnsigned(valOffsets, code + 1, valOffPType);
            long strLen = end - start;
            if (strLen > 0) {
                MemorySegment.copy(valBytes, start, outBytes, bytePos, strLen);
                bytePos += strLen;
            }
            outOffsets.setAtIndex(LE_INT, i + 1, (int) bytePos);
        }

        return new VarBinArray(dtype, n, outBytes.asReadOnly(), outOffsets.asReadOnly(), PType.I32);
    }

    // ── Flat segment decoding ─────────────────────────────────────────────────

    private static long readUnsigned(MemorySegment seg, long idx, PType ptype) {
        return switch (ptype) {
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, idx));
            case U16 -> Short.toUnsignedLong(seg.get(LE_SHORT, idx * 2));
            case U32 -> Integer.toUnsignedLong(seg.getAtIndex(LE_INT, idx));
            case I32 -> seg.getAtIndex(LE_INT, idx);
            case I64, U64 -> seg.getAtIndex(LE_LONG, idx);
            default -> throw new VortexException(EncodingId.VORTEX_DICT, "layout: unsupported ptype " + ptype);
        };
    }

    // ── Zone-map pruning ──────────────────────────────────────────────────────

    private static Map<String, Array> truncateColumns(Map<String, Array> columns, long rows) {
        var result = new LinkedHashMap<String, Array>(columns.size());
        for (var entry : columns.entrySet()) {
            result.put(entry.getKey(), truncateArray(entry.getValue(), rows));
        }
        return Map.copyOf(result);
    }

    private static Array truncateArray(Array arr, long rows) {
        if (arr.length() <= rows) {
            return arr;
        }
        return switch (arr) {
            case LongArray a ->
                    new LongArray(a.dtype(), rows, ArraySegments.of(a).asSlice(0, rows * Long.BYTES));
            case IntArray a ->
                    new IntArray(a.dtype(), rows, ArraySegments.of(a).asSlice(0, rows * Integer.BYTES));
            case DoubleArray a ->
                    new DoubleArray(a.dtype(), rows, ArraySegments.of(a).asSlice(0, rows * Double.BYTES));
            case FloatArray a ->
                    new FloatArray(a.dtype(), rows, ArraySegments.of(a).asSlice(0, rows * Float.BYTES));
            case ShortArray a ->
                    new ShortArray(a.dtype(), rows, ArraySegments.of(a).asSlice(0, rows * Short.BYTES));
            case ByteArray a -> new ByteArray(a.dtype(), rows, ArraySegments.of(a).asSlice(0, rows));
            case BoolArray a ->
                    new BoolArray(a.dtype(), rows, ArraySegments.of(a).asSlice(0, (rows + 7) / 8));
            case NullArray a -> new NullArray(a.dtype(), rows);
            case VarBinArray a -> a.truncate(rows);
            case MaskedArray a -> {
                Array truncChild = truncateArray(a.inner(), rows);
                BoolArray v = a.validity();
                BoolArray truncValidity = (v != null) ? (BoolArray) truncateArray(v, rows) : null;
                yield new MaskedArray(truncChild, truncValidity);
            }
            case EmptyArray a -> a;
            default ->
                    throw new VortexException("limit: truncation not supported for " + arr.getClass().getSimpleName());
        };
    }

    public boolean hasNext() {
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

            if (chunkArena != null) {
                chunkArena.close();
            }
            chunkArena = Arena.ofConfined();

            long remaining = options.limit() - rowsReturned;
            long chunkRows = Math.min(chunk.rowCount(), remaining);
            Map<String, Array> columns = buildColumnMap(chunk);
            if (chunkRows < chunk.rowCount()) {
                columns = truncateColumns(columns, chunkRows);
            }
            current = new ScanResult(chunkRows, columns);
            rowsReturned += chunkRows;
            return true;
        }
        // Do not close here — the last chunk's arena stays open until close() is called.
        // This preserves data validity for callers that collect results before processing.
        return false;
    }

    public ScanResult next() {
        if (current == null) {
            throw new VortexException("call hasNext() first");
        }
        return current;
    }

    @Override
    public void close() {
        if (chunkArena != null) {
            chunkArena.close();
            chunkArena = null;
        }
    }

    private void initialize() {
        Layout rootLayout = file.layout();
        DType rootDtype = file.dtype();

        var columnFlats = new LinkedHashMap<String, List<Layout>>();
        Map<String, DType> columnDtypes = new LinkedHashMap<>();

        if (rootLayout.isStruct() && rootDtype instanceof DType.Struct structDtype) {
            List<String> projection = options.columns();
            for (int i = 0; i < rootLayout.children().size(); i++) {
                String colName = structDtype.fieldNames().get(i);
                DType colDtype = structDtype.fieldTypes().get(i);
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
        projectedNames = List.copyOf(columnDtypes.keySet());
        projectedDtypes = List.copyOf(columnDtypes.values());
    }

    // Map.of with 1 or 2 args allocates Map1/Map2 (~2-4 fields) — avoids the
    // LinkedHashMap + Map.copyOf pair that would otherwise allocate per chunk.
    // Direct array index into ChunkSpec.columnLayouts avoids HashMap.get() per column.
    private Map<String, Array> buildColumnMap(ChunkSpec chunk) {
        Layout[] layouts = chunk.columnLayouts();
        int n = projectedNames.size();
        if (n == 1) {
            Array arr = decodeLayout(layouts[0], projectedDtypes.getFirst(), chunkArena);
            if (arr instanceof StructArray sa) {
                return expandStruct(sa);
            }
            return Map.of(projectedNames.getFirst(), arr);
        }
        if (n == 2) {
            return Map.of(
                    projectedNames.get(0), decodeLayout(layouts[0], projectedDtypes.get(0), chunkArena),
                    projectedNames.get(1), decodeLayout(layouts[1], projectedDtypes.get(1), chunkArena));
        }
        var scratch = new LinkedHashMap<String, Array>(n);
        for (int i = 0; i < n; i++) {
            scratch.put(projectedNames.get(i), decodeLayout(layouts[i], projectedDtypes.get(i), chunkArena));
        }
        return Map.copyOf(scratch);
    }

    private Array decodeLayout(Layout layout, DType dtype, SegmentAllocator arena) {
        if (layout.isFlat()) {
            return decodeFlat(layout, dtype, arena);
        }
        if (layout.isDict()) {
            return decodeDictLayout(layout, dtype, arena);
        }
        if (layout.isZoned() && !layout.children().isEmpty()) {
            return decodeLayout(layout.children().getFirst(), dtype, arena);
        }
        if (layout.isChunked()) {
            var flats = new ArrayList<Layout>();
            collectFlats(layout, flats);
            return decodeConcatPrimitive(flats, dtype, layout.rowCount(), arena);
        }
        throw new VortexException("cannot decode layout " + layout.encodingId());
    }

    private Array decodeConcatPrimitive(List<Layout> flats, DType dtype, long totalRows, SegmentAllocator arena) {
        if (flats.isEmpty()) {
            throw new VortexException(EncodingId.VORTEX_CHUNKED, "no flat children");
        }
        if (flats.size() == 1) {
            return decodeFlat(flats.getFirst(), dtype, arena);
        }
        PType ptype = ((DType.Primitive) dtype).ptype();
        MemorySegment combined = arena.allocate(totalRows * ptype.byteSize());
        long byteOffset = 0;
        for (Layout flat : flats) {
            Array chunk = decodeFlat(flat, dtype, arena);
            Array chunkData = chunk instanceof MaskedArray m ? m.inner() : chunk;
            MemorySegment src = ArraySegments.of(chunkData);
            MemorySegment.copy(src, 0, combined, byteOffset, src.byteSize());
            byteOffset += src.byteSize();
        }
        MemorySegment ro = combined.asReadOnly();
        return switch (ptype) {
            case I64, U64 -> new LongArray(dtype, totalRows, ro);
            case I32, U32 -> new IntArray(dtype, totalRows, ro);
            case F64 -> new DoubleArray(dtype, totalRows, ro);
            case F32 -> new FloatArray(dtype, totalRows, ro);
            case I16, U16 -> new ShortArray(dtype, totalRows, ro);
            case I8, U8 -> new ByteArray(dtype, totalRows, ro);
            default -> throw new VortexException("unsupported ptype for concat: " + ptype);
        };
    }

    // ── Limit truncation ─────────────────────────────────────────────────────

    private Array decodeFlat(Layout flat, DType dtype, SegmentAllocator arena) {
        if (flat.segments().isEmpty()) {
            throw new VortexException("no segments");
        }
        int segIdx = flat.segments().getFirst();
        SegmentSpec spec = file.footer().segmentSpecs().get(segIdx);
        MemorySegment seg = file.slice(spec.offset(), spec.length());
        return new FlatSegmentDecoder(registry).decode(seg, file.footer().arraySpecs(), dtype, flat.rowCount(), arena);
    }

    private Array decodeDictLayout(Layout dictLayout, DType dtype, SegmentAllocator arena) {
        ByteBuffer rawMeta = dictLayout.metadata();
        // DictLayoutMetadata proto (Rust format): field 1 = codes_ptype (PType varint).
        // Read the varint directly to avoid field-number mismatch with the array-level DictMetadata proto.
        PType codesPType = readDictLayoutCodesPType(rawMeta);

        // child[0] = values layout; child[1] = codes layout
        Layout valuesLayout = dictLayout.children().get(0);
        Layout codesLayout = dictLayout.children().get(1);
        long n = codesLayout.rowCount();

        Array values = decodeLayout(valuesLayout, dtype, arena);
        Array codes = decodeLayout(codesLayout, new DType.Primitive(codesPType, false), arena);

        MemorySegment codesSeg = ArraySegments.of(codes);

        // Zip-bomb guard: for direct-mapped encodings (e.g. vortex.primitive), the codes
        // buffer is mmap-bounded and can be much smaller than the claimed rowCount. Reject
        // before any O(n) allocation so an inflated layout row_count cannot trigger OOM.
        // Full-decode encodings (e.g. bitpacked) write n * elemBytes to the arena during
        // decodeLayout above, so their buffer will already match n — check passes for them.
        long bufferCodes = codesSeg.byteSize() / (long) codesPType.byteSize();
        if (bufferCodes < n) {
            throw new VortexException(EncodingId.VORTEX_DICT,
                    "dict codes: layout row_count=" + n + " exceeds buffer capacity=" + bufferCodes);
        }

        if (values instanceof VarBinArray vb) {
            MemorySegment valOffsets = vb.offsetsSegment();
            PType valOffPType = vb.offsetsPtype();
            return VarBinArray.ofDict(dtype, n, vb.bytesSegment(), valOffsets, valOffPType,
                    codesSeg, codesPType);
        }
        if (dtype instanceof DType.Primitive pDtype) {
            MemorySegment valBuf = ArraySegments.of(values);
            return expandDictPrimitive(valBuf, codesSeg, codesPType, pDtype, n, arena);
        }
        return expandDictStrings((VarBinArray) values, codesSeg, codesPType, dtype, n, arena);
    }

    private static PType readDictLayoutCodesPType(ByteBuffer rawMeta) {
        // DictLayoutMetadata (Rust): field 1 = codes_ptype, wire type 0 (varint).
        // Tag byte = (field_number << 3) | wire_type = (1 << 3) | 0 = 0x08.
        // Proto3 omits field 1 when it holds the default value (0 = U8), so empty metadata means U8.
        if (rawMeta == null || !rawMeta.hasRemaining()) {
            return PType.U8;
        }
        ByteBuffer buf = rawMeta.duplicate();
        byte tag = buf.get();
        if (tag == 0x08 && buf.hasRemaining()) {
            int ordinal = buf.get() & 0xFF;
            PType[] values = PType.values();
            if (ordinal < values.length) {
                return values[ordinal];
            }
        }
        return PType.U8;
    }

    private boolean canPruneChunk(ChunkSpec chunk, RowFilter filter) {
        return switch (filter) {
            case RowFilter.And(var filters) -> {
                for (RowFilter f : filters) {
                    if (canPruneChunk(chunk, f)) {
                        yield true;
                    }
                }
                yield false;
            }
            case RowFilter.Gt(var col, var val) -> {
                Layout flat = chunk.layoutFor(col);
                if (flat == null) {
                    yield false;
                }
                Object max = readFlatStats(flat).max();
                yield max != null && compareValues(max, val) <= 0;
            }
            case RowFilter.Gte(var col, var val) -> {
                Layout flat = chunk.layoutFor(col);
                if (flat == null) {
                    yield false;
                }
                Object max = readFlatStats(flat).max();
                yield max != null && compareValues(max, val) < 0;
            }
            case RowFilter.Lt(var col, var val) -> {
                Layout flat = chunk.layoutFor(col);
                if (flat == null) {
                    yield false;
                }
                Object min = readFlatStats(flat).min();
                yield min != null && compareValues(min, val) >= 0;
            }
            case RowFilter.Lte(var col, var val) -> {
                Layout flat = chunk.layoutFor(col);
                if (flat == null) {
                    yield false;
                }
                Object min = readFlatStats(flat).min();
                yield min != null && compareValues(min, val) > 0;
            }
            case RowFilter.Eq(var col, var val) -> {
                Layout flat = chunk.layoutFor(col);
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
            case RowFilter.Neq(var col, var val) -> {
                Layout flat = chunk.layoutFor(col);
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
                    yield cv.compareTo(min) == 0 && cv.compareTo(max) == 0;
                } catch (ClassCastException e) {
                    yield false;
                }
            }
        };
    }

    private ArrayStats readFlatStats(Layout flat) {
        if (flat.segments().isEmpty()) {
            return ArrayStats.empty();
        }
        int segIdx = flat.segments().getFirst();
        SegmentSpec spec = file.footer().segmentSpecs().get(segIdx);
        long segLen = spec.length();
        MemorySegment seg = file.slice(spec.offset(), segLen);

        // Stats FlatBuffer lives in the segment's last 4+fbLen bytes; reading the whole
        // segment as a ByteBuffer would fail for segments larger than 2 GB (ByteBuffer cap).
        int fbLen = seg.get(LE_INT, segLen - 4);
        long fbStart = segLen - 4L - fbLen;
        ByteBuffer fbBuf = seg.asSlice(fbStart, fbLen).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        var fbArray = io.github.dfa1.vortex.fbs.Array.getRootAsArray(fbBuf);

        io.github.dfa1.vortex.fbs.ArrayNode root = fbArray.root();
        if (root == null) {
            return ArrayStats.empty();
        }
        return ArrayStats.fromFbs(root.stats());
    }

    // ── Internal record ───────────────────────────────────────────────────────

    private record ChunkSpec(long rowCount, String[] columnNames, Layout[] columnLayouts) {
        Layout layoutFor(String col) {
            for (int i = 0; i < columnNames.length; i++) {
                if (columnNames[i].equals(col)) {
                    return columnLayouts[i];
                }
            }
            return null;
        }
    }
}
