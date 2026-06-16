package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.ChunkedBoolArray;
import io.github.dfa1.vortex.reader.array.ChunkedByteArray;
import io.github.dfa1.vortex.reader.array.ChunkedDoubleArray;
import io.github.dfa1.vortex.reader.array.ChunkedFloatArray;
import io.github.dfa1.vortex.reader.array.ChunkedIntArray;
import io.github.dfa1.vortex.reader.array.ChunkedLongArray;
import io.github.dfa1.vortex.reader.array.ChunkedShortArray;
import io.github.dfa1.vortex.reader.array.DictDoubleArray;
import io.github.dfa1.vortex.reader.array.DictFloatArray;
import io.github.dfa1.vortex.reader.array.DictIntArray;
import io.github.dfa1.vortex.reader.array.DictLongArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.EmptyArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.GenericArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/// Iterates over decoded chunks from a [io.github.dfa1.vortex.reader.VortexReader].
///
/// Each call to [#next()] returns a [Chunk] that owns a confined
/// [Arena]; the caller closes it via try-with-resources. The iterator itself
/// is also [AutoCloseable] — closing it releases any chunk still open.
///
/// Usage:
/// ```java
/// try (var iter = file.scan(ScanOptions.all())) {
///     while (iter.hasNext()) {
///         try (Chunk chunk = iter.next()) {
///             // read columns; refs invalid after chunk.close()
///         }
///     }
/// }
/// ```
///
/// `forEachRemaining` is overridden to run each chunk inside a
/// try-with-resources block automatically:
///
/// ```java
/// try (var iter = file.scan(opts)) {
///     iter.forEachRemaining(chunk -> sum += sumColumn(chunk, "price"));
/// }
/// ```
public final class ScanIterator implements Iterator<Chunk>, AutoCloseable {

    private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final VortexHandle file;
    private final ScanOptions options;

    private List<ChunkSpec> chunks;
    private List<String> projectedNames;
    private List<DType> projectedDtypes;
    private int chunkIndex;
    private int peekedChunkIdx = -1;
    private long rowsReturned;
    private Chunk openChunk;
    private boolean closed;

    public ScanIterator(VortexHandle file, ScanOptions options) {
        this.file = file;
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

    // ── Column map builder ────────────────────────────────────────────────────

    private static Array expandDictStrings(
            VarBinArray.OffsetMode values, MemorySegment codesSegs,
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

        return new VarBinArray.OffsetMode(dtype, n, outBytes.asReadOnly(), outOffsets.asReadOnly(), PType.I32);
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

    private static Map<String, Array> truncateColumns(Map<String, Array> columns, long rows,
                                                       SegmentAllocator arena) {
        var result = new LinkedHashMap<String, Array>(columns.size());
        for (var entry : columns.entrySet()) {
            result.put(entry.getKey(), truncateArray(entry.getValue(), rows, arena));
        }
        return Map.copyOf(result);
    }

    private static Array truncateArray(Array arr, long rows, SegmentAllocator arena) {
        if (arr.length() <= rows) {
            return arr;
        }
        // Chunked* cases must precede the LongArray/IntArray/etc catch-alls below — a
        // ChunkedLongArray IS a LongArray, but slicing it via ArraySegments.of would
        // materialise the entire column before slicing (defeating the zero-copy win
        // we just added). Instead, keep the prefix children intact and recursively
        // truncate the boundary child.
        return switch (arr) {
            case ChunkedLongArray a -> truncateChunkedLong(a, rows, arena);
            case ChunkedIntArray a -> truncateChunkedInt(a, rows, arena);
            case ChunkedDoubleArray a -> truncateChunkedDouble(a, rows, arena);
            case ChunkedFloatArray a -> truncateChunkedFloat(a, rows, arena);
            case ChunkedShortArray a -> truncateChunkedShort(a, rows, arena);
            case ChunkedByteArray a -> truncateChunkedByte(a, rows, arena);
            case ChunkedBoolArray a -> truncateChunkedBool(a, rows, arena);
            // Dict* cases must precede the LongArray/etc catch-alls below: a DictLongArray
            // IS a LongArray, but the catch-all materialises via ArraySegments.of which
            // would scatter the entire column to truncate. Instead keep the values
            // dictionary intact and just truncate the codes — codes are a primitive Array
            // that recursively flows through this same switch.
            case DictLongArray a ->
                    DictLongArray.of(a.dtype(), rows, a.values(), truncateArray(a.codes(), rows, arena));
            case DictIntArray a ->
                    DictIntArray.of(a.dtype(), rows, a.values(), truncateArray(a.codes(), rows, arena));
            case DictDoubleArray a ->
                    DictDoubleArray.of(a.dtype(), rows, a.values(), truncateArray(a.codes(), rows, arena));
            case DictFloatArray a ->
                    DictFloatArray.of(a.dtype(), rows, a.values(), truncateArray(a.codes(), rows, arena));
            case LongArray a ->
                    new MaterializedLongArray(a.dtype(), rows, ArraySegments.of(a, arena).asSlice(0, rows * Long.BYTES));
            case IntArray a ->
                    new MaterializedIntArray(a.dtype(), rows, ArraySegments.of(a, arena).asSlice(0, rows * Integer.BYTES));
            case DoubleArray a ->
                    new MaterializedDoubleArray(a.dtype(), rows, ArraySegments.of(a, arena).asSlice(0, rows * Double.BYTES));
            case FloatArray a ->
                    new MaterializedFloatArray(a.dtype(), rows, ArraySegments.of(a, arena).asSlice(0, rows * Float.BYTES));
            case ShortArray a ->
                    new MaterializedShortArray(a.dtype(), rows, ArraySegments.of(a, arena).asSlice(0, rows * Short.BYTES));
            case ByteArray a -> new MaterializedByteArray(a.dtype(), rows, ArraySegments.of(a, arena).asSlice(0, rows));
            case BoolArray a ->
                    new MaterializedBoolArray(a.dtype(), rows, ArraySegments.of(a, arena).asSlice(0, (rows + 7) / 8));
            case NullArray a -> new NullArray(a.dtype(), rows);
            case VarBinArray a -> a.truncate(rows);
            case MaskedArray a -> {
                Array truncChild = truncateArray(a.inner(), rows, arena);
                BoolArray v = a.validity();
                BoolArray truncValidity = (v != null) ? (BoolArray) truncateArray(v, rows, arena) : null;
                yield new MaskedArray(truncChild, truncValidity);
            }
            case EmptyArray a -> a;
            case GenericArray a -> a.withLength(rows);
            default ->
                    throw new VortexException("limit: truncation not supported for " + arr.getClass().getSimpleName());
        };
    }

    /// Truncates a `ChunkedXxxArray` by keeping full children that fit within
    /// `rows` and recursively truncating the boundary child. Avoids the
    /// full-column materialisation that the {@link LongArray}/{@link IntArray}/etc.
    /// catch-all cases would trigger via {@link ArraySegments#of(Array, SegmentAllocator)}.
    private static Array truncateChunkedLong(ChunkedLongArray arr, long rows, SegmentAllocator arena) {
        List<Array> kept = collectTruncatedChildren(arr.children(), arr.offsets(), rows, arena);
        return ChunkedLongArray.of(arr.dtype(), rows, kept);
    }

    private static Array truncateChunkedInt(ChunkedIntArray arr, long rows, SegmentAllocator arena) {
        List<Array> kept = collectTruncatedChildren(arr.children(), arr.offsets(), rows, arena);
        return ChunkedIntArray.of(arr.dtype(), rows, kept);
    }

    private static Array truncateChunkedDouble(ChunkedDoubleArray arr, long rows, SegmentAllocator arena) {
        List<Array> kept = collectTruncatedChildren(arr.children(), arr.offsets(), rows, arena);
        return ChunkedDoubleArray.of(arr.dtype(), rows, kept);
    }

    private static Array truncateChunkedFloat(ChunkedFloatArray arr, long rows, SegmentAllocator arena) {
        List<Array> kept = collectTruncatedChildren(arr.children(), arr.offsets(), rows, arena);
        return ChunkedFloatArray.of(arr.dtype(), rows, kept);
    }

    private static Array truncateChunkedShort(ChunkedShortArray arr, long rows, SegmentAllocator arena) {
        List<Array> kept = collectTruncatedChildren(arr.children(), arr.offsets(), rows, arena);
        return ChunkedShortArray.of(arr.dtype(), rows, kept);
    }

    private static Array truncateChunkedByte(ChunkedByteArray arr, long rows, SegmentAllocator arena) {
        List<Array> kept = collectTruncatedChildren(arr.children(), arr.offsets(), rows, arena);
        return ChunkedByteArray.of(arr.dtype(), rows, kept);
    }

    private static Array truncateChunkedBool(ChunkedBoolArray arr, long rows, SegmentAllocator arena) {
        List<Array> kept = collectTruncatedChildren(arr.children(), arr.offsets(), rows, arena);
        return ChunkedBoolArray.of(arr.dtype(), rows, kept);
    }

    private static List<Array> collectTruncatedChildren(Array[] children, long[] offsets,
                                                        long rows, SegmentAllocator arena) {
        var kept = new ArrayList<Array>(children.length);
        for (int i = 0; i < children.length; i++) {
            long start = offsets[i];
            long end = offsets[i + 1];
            if (start >= rows) {
                break;
            }
            if (end <= rows) {
                kept.add(children[i]);
            } else {
                kept.add(truncateArray(children[i], rows - start, arena));
            }
        }
        return kept;
    }

    @Override
    public boolean hasNext() {
        if (closed) {
            return false;
        }
        if (chunks == null) {
            initialize();
        }
        if (peekedChunkIdx >= 0) {
            return true;
        }
        if (rowsReturned >= options.limit()) {
            return false;
        }
        while (chunkIndex < chunks.size()) {
            ChunkSpec spec = chunks.get(chunkIndex);
            if (options.hasFilter() && canPruneChunk(spec, options.rowFilter())) {
                chunkIndex++;
                continue;
            }
            peekedChunkIdx = chunkIndex;
            return true;
        }
        return false;
    }

    @Override
    public Chunk next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        if (openChunk != null && !openChunk.isClosed()) {
            throw new IllegalStateException(
                    "previous Chunk is still open — close it before calling next()");
        }
        ChunkSpec spec = chunks.get(peekedChunkIdx);
        chunkIndex = peekedChunkIdx + 1;
        peekedChunkIdx = -1;

        long remaining = options.limit() - rowsReturned;
        long chunkRows = Math.min(spec.rowCount(), remaining);

        Arena arena = Arena.ofConfined();
        try {
            Map<String, Array> columns = buildColumnMap(spec, arena);
            if (chunkRows < spec.rowCount()) {
                columns = truncateColumns(columns, chunkRows, arena);
            }
            rowsReturned += chunkRows;
            Map<String, DType> chunkDtypes = new java.util.LinkedHashMap<>();
            for (int i = 0; i < projectedNames.size(); i++) {
                chunkDtypes.put(projectedNames.get(i), projectedDtypes.get(i));
            }
            Chunk chunk = new Chunk(chunkRows, columns, chunkDtypes, arena, this::onChunkClosed);
            openChunk = chunk;
            return chunk;
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /// Runs `action` on each remaining chunk inside a try-with-resources
    /// block so every chunk's [Arena] is released before the next iteration.
    /// Prefer this over a manual `while (hasNext()) { next(); `} loop
    /// when no early-exit is needed.
    ///
    /// @param action consumer invoked once per remaining chunk
    @Override
    public void forEachRemaining(Consumer<? super Chunk> action) {
        while (hasNext()) {
            try (Chunk c = next()) {
                action.accept(c);
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (openChunk != null && !openChunk.isClosed()) {
            openChunk.close();
        }
        openChunk = null;
    }

    private void onChunkClosed(Chunk chunk) {
        if (openChunk == chunk) {
            openChunk = null;
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
    private Map<String, Array> buildColumnMap(ChunkSpec chunk, Arena arena) {
        Layout[] layouts = chunk.columnLayouts();
        int n = projectedNames.size();
        if (n == 1) {
            Array arr = decodeLayout(layouts[0], projectedDtypes.getFirst(), arena);
            if (arr instanceof StructArray sa) {
                return expandStruct(sa);
            }
            return Map.of(projectedNames.getFirst(), arr);
        }
        if (n == 2) {
            return Map.of(
                    projectedNames.get(0), decodeLayout(layouts[0], projectedDtypes.get(0), arena),
                    projectedNames.get(1), decodeLayout(layouts[1], projectedDtypes.get(1), arena));
        }
        var scratch = new LinkedHashMap<String, Array>(n);
        for (int i = 0; i < n; i++) {
            scratch.put(projectedNames.get(i), decodeLayout(layouts[i], projectedDtypes.get(i), arena));
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
            return decodeChunkedLayout(flats, dtype, layout.rowCount(), arena);
        }
        throw new VortexException("cannot decode layout " + layout.encodingId());
    }

    private Array decodeChunkedLayout(List<Layout> flats, DType dtype, long totalRows, SegmentAllocator arena) {
        if (flats.isEmpty()) {
            throw new VortexException(EncodingId.VORTEX_CHUNKED, "no flat children");
        }
        if (flats.size() == 1) {
            return decodeFlat(flats.getFirst(), dtype, arena);
        }
        // ADR 0012: every primitive ptype gets the zero-copy ChunkedXxxArray shape.
        // The concat path is gone.
        var chunkArrays = new ArrayList<Array>(flats.size());
        for (Layout flat : flats) {
            chunkArrays.add(decodeFlat(flat, dtype, arena));
        }
        if (dtype instanceof DType.Bool) {
            return ChunkedBoolArray.of(dtype, totalRows, chunkArrays);
        }
        if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
            return VarBinArray.ChunkedMode.of(dtype, totalRows, chunkArrays);
        }
        PType ptype = ((DType.Primitive) dtype).ptype();
        return switch (ptype) {
            case I64, U64 -> ChunkedLongArray.of(dtype, totalRows, chunkArrays);
            case I32, U32 -> ChunkedIntArray.of(dtype, totalRows, chunkArrays);
            case F64 -> ChunkedDoubleArray.of(dtype, totalRows, chunkArrays);
            case F32 -> ChunkedFloatArray.of(dtype, totalRows, chunkArrays);
            case I16, U16 -> ChunkedShortArray.of(dtype, totalRows, chunkArrays);
            case I8, U8 -> ChunkedByteArray.of(dtype, totalRows, chunkArrays);
            default -> throw new VortexException("unsupported ptype for chunked layout: " + ptype);
        };
    }

    // ── Limit truncation ─────────────────────────────────────────────────────

    private Array decodeFlat(Layout flat, DType dtype, SegmentAllocator arena) {
        if (flat.segments().isEmpty()) {
            throw new VortexException("no segments");
        }
        int segIdx = flat.segments().getFirst();
        SegmentSpec spec = file.footer().segmentSpecs().get(segIdx);
        return file.decodeFlatSegment(spec, dtype, flat.rowCount(), arena);
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

        // VarBin (string) dict: VarBinArray is a sealed interface; ofDict returns the
        // lazy DictMode record (no eager expansion into per-row offsets/bytes).
        if (values instanceof VarBinArray.OffsetMode vb) {
            // Zip-bomb guard: read the codes as a segment so we can validate the buffer
            // before allocating the expansion output. For direct-mapped encodings (e.g.
            // vortex.primitive), the codes buffer is mmap-bounded and can be much smaller
            // than the claimed rowCount. Full-decode encodings (e.g. bitpacked) already
            // wrote n * elemBytes to the arena during decodeLayout above, so their buffer
            // matches n.
            MemorySegment codesSeg = ArraySegments.of(codes, arena);
            long bufferCodes = codesSeg.byteSize() / (long) codesPType.byteSize();
            if (bufferCodes < n) {
                throw new VortexException(EncodingId.VORTEX_DICT,
                        "dict codes: layout row_count=" + n + " exceeds buffer capacity=" + bufferCodes);
            }
            MemorySegment valOffsets = vb.offsetsSegment();
            PType valOffPType = vb.offsetsPtype();
            return VarBinArray.ofDict(dtype, n, vb.bytesSegment(), valOffsets, valOffPType,
                    codesSeg, codesPType);
        }
        if (dtype instanceof DType.Primitive pDtype) {
            // Zip-bomb guard (lazy path): the codes Array has already been decoded above;
            // its length() reflects the claimed rowCount but its backing buffer may be
            // mmap-bounded. Validate by inspecting the underlying segment without forcing
            // materialisation of non-segment-backed codes (lazy variants).
            validateDictCodesCapacity(codes, codesPType, n);
            return buildLazyDictPrimitive(pDtype, n, values, codes);
        }
        // Non-Utf8, non-Primitive dict — e.g. extension types backed by VarBin. Fall through
        // to the existing string expansion for compatibility.
        MemorySegment codesSegFallback = ArraySegments.of(codes, arena);
        long bufferCodesFallback = codesSegFallback.byteSize() / (long) codesPType.byteSize();
        if (bufferCodesFallback < n) {
            throw new VortexException(EncodingId.VORTEX_DICT,
                    "dict codes: layout row_count=" + n + " exceeds buffer capacity=" + bufferCodesFallback);
        }
        return expandDictStrings(VarBinArray.toOffsetMode((VarBinArray) values, arena),
                codesSegFallback, codesPType, dtype, n, arena);
    }

    /// Lazy-path zip-bomb guard. Inspects `codes`'s primary segment when available
    /// (segment-backed encodings can be mmap-bounded and undersized); skips validation
    /// for non-segment variants whose own decoder has already enforced length.
    ///
    /// @param codes      the decoded codes array
    /// @param codesPType code ptype reported by the dict layout metadata
    /// @param n          claimed dict row count
    private static void validateDictCodesCapacity(Array codes, PType codesPType, long n) {
        MemorySegment seg;
        try {
            seg = ArraySegments.of(codes);
        } catch (VortexException e) {
            return;
        }
        long bufferCodes = seg.byteSize() / (long) codesPType.byteSize();
        if (bufferCodes < n) {
            throw new VortexException(EncodingId.VORTEX_DICT,
                    "dict codes: layout row_count=" + n + " exceeds buffer capacity=" + bufferCodes);
        }
    }

    /// Builds the matching `DictXxxArray` for a primitive dictionary, unwrapping
    /// any {@link MaskedArray} layer on either side — dictionary lookups are keyed by code
    /// so value-side validity is meaningless at this layer.
    ///
    /// @param dtype  primitive logical type of dict values
    /// @param n      total logical row count
    /// @param values dictionary values
    /// @param codes  per-row codes into `values`
    /// @return a lazy `DictXxxArray` matching the value ptype
    private static Array buildLazyDictPrimitive(DType.Primitive dtype, long n, Array values, Array codes) {
        Array valuesData = values instanceof MaskedArray mv ? mv.inner() : values;
        Array codesData = codes instanceof MaskedArray mc ? mc.inner() : codes;
        PType ptype = dtype.ptype();
        return switch (ptype) {
            case I64, U64 -> DictLongArray.of(dtype, n, (LongArray) valuesData, codesData);
            case I32, U32 -> DictIntArray.of(dtype, n, (IntArray) valuesData, codesData);
            case F64 -> DictDoubleArray.of(dtype, n, (DoubleArray) valuesData, codesData);
            case F32 -> DictFloatArray.of(dtype, n, (FloatArray) valuesData, codesData);
            default -> throw new VortexException(EncodingId.VORTEX_DICT,
                    "layout: unsupported ptype for lazy dict: " + ptype);
        };
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
        MemorySegment seg = file.rawSegment(spec);

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

    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
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
