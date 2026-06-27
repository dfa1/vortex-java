package io.github.dfa1.vortex.reader;

import static io.github.dfa1.vortex.core.io.PTypeIO.LE_SHORT;
import static io.github.dfa1.vortex.core.io.PTypeIO.LE_INT;
import static io.github.dfa1.vortex.core.io.PTypeIO.LE_LONG;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.array.Array;
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
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.OffsetBoolArray;
import io.github.dfa1.vortex.reader.array.OffsetByteArray;
import io.github.dfa1.vortex.reader.array.OffsetDoubleArray;
import io.github.dfa1.vortex.reader.array.OffsetFloatArray;
import io.github.dfa1.vortex.reader.array.OffsetIntArray;
import io.github.dfa1.vortex.reader.array.OffsetLongArray;
import io.github.dfa1.vortex.reader.array.OffsetShortArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
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


    private final VortexHandle file;
    private final ScanOptions options;

    private List<ChunkSpec> chunks;
    private List<String> projectedNames;
    private List<DType> projectedDtypes;
    private Map<String, DType> columnDtypes;
    private int chunkIndex;
    private int peekedChunkIdx = -1;
    private long rowsReturned;
    private Chunk openChunk;
    private boolean closed;
    private Arena sharedArena;
    private Map<String, Array> sharedFullArrays;

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
                                 && layout.metadata().byteSize() > 0
                                 && layout.metadata().get(ValueLayout.JAVA_BYTE, 0) == 1) ? 1 : 0;
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
        int maxChunks = 0;
        int refCol = 0;
        for (int j = 0; j < numCols; j++) {
            int n = columnFlats.get(colNames[j]).size();
            if (n > maxChunks) {
                maxChunks = n;
                refCol = j;
            }
        }
        // Detect single-flat columns sharing the chunked range of a wider column.
        // Other mismatched widths (e.g. 5 flats vs 23 flats) are not supported.
        boolean[] shared = new boolean[numCols];
        for (int j = 0; j < numCols; j++) {
            int n = columnFlats.get(colNames[j]).size();
            if (n == maxChunks) {
                continue;
            }
            if (n == 1) {
                shared[j] = true;
            } else {
                throw new VortexException(
                        "scan: column '" + colNames[j] + "' has " + n
                                + " flats but the widest column has " + maxChunks
                                + "; mixed per-column chunking beyond 1-vs-N is not supported");
            }
        }
        var result = new ArrayList<ChunkSpec>(maxChunks);
        long sliceStart = 0;
        for (int i = 0; i < maxChunks; i++) {
            long chunkRowCount = columnFlats.get(colNames[refCol]).get(i).rowCount();
            result.add(buildChunkSpec(colNames, columnFlats, shared, i, sliceStart, chunkRowCount));
            sliceStart += chunkRowCount;
        }
        return List.copyOf(result);
    }

    private static ChunkSpec buildChunkSpec(String[] colNames, Map<String, List<Layout>> columnFlats,
            boolean[] shared, int chunkIdx, long sliceStart, long chunkRowCount) {
        int numCols = colNames.length;
        Layout[] layouts = new Layout[numCols];
        long[] sliceOffsets = new long[numCols];
        for (int j = 0; j < numCols; j++) {
            if (shared[j]) {
                layouts[j] = null;
                sliceOffsets[j] = sliceStart;
            } else {
                layouts[j] = columnFlats.get(colNames[j]).get(chunkIdx);
                sliceOffsets[j] = 0L;
            }
        }
        return new ChunkSpec(chunkRowCount, colNames, layouts, sliceOffsets);
    }

    // ── Layout tree traversal ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static int compareValues(Object a, Object b, DType column) {
        // Key the compare mode off the *column* type, not the boxed operand type. Stats decode
        // integers as Long and floats as Float/Double, and a caller may box a filter value at the
        // column's natural width (Integer for I32) or in a different width entirely. Letting the
        // column decide keeps pruning width-agnostic (issue #159) without ever routing an integer
        // column through double-compare (which would lose precision past 2^53 and mis-prune).
        if (a instanceof Number na && b instanceof Number nb) {
            if (column instanceof DType.Primitive prim) {
                if (prim.ptype().isFloating()) {
                    return Double.compare(na.doubleValue(), nb.doubleValue());
                }
                // U64 stats/values store the raw 64 bits, so a value >= 2^63 is a negative Long; an
                // unsigned column must compare unsigned. U8/U16/U32 are zero-extended to a positive
                // Long where signed == unsigned, so this stays correct for them too.
                return column.isUnsigned()
                        ? Long.compareUnsigned(na.longValue(), nb.longValue())
                        : Long.compare(na.longValue(), nb.longValue());
            }
            // Column type unresolved (not a struct field) — fall back to a width-agnostic compare
            // keyed off the operands so two valid numbers never drop into the throwing path.
            if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            return Long.compare(na.longValue(), nb.longValue());
        }
        try {
            return ((Comparable<Object>) a).compareTo(b);
        } catch (ClassCastException e) {
            // A genuinely incomparable filter value (e.g. a String against a numeric column) is a
            // caller error — surface it instead of swallowing it into a silent no-prune.
            throw new VortexException("filter value of type " + b.getClass().getSimpleName()
                    + " is not comparable to the column's zone-map statistic of type "
                    + a.getClass().getSimpleName(), e);
        }
    }

    /// Returns the declared [DType] of column `col`, or `null` if the file is not a struct or has
    /// no such column. Resolved once from the file's struct schema and cached; used to drive
    /// zone-map comparisons by the column's true type rather than the filter value's boxing.
    private DType columnDType(String col) {
        if (columnDtypes == null) {
            columnDtypes = new HashMap<>();
            if (file.dtype() instanceof DType.Struct struct) {
                for (int i = 0; i < struct.fieldNames().size(); i++) {
                    columnDtypes.put(struct.fieldNames().get(i), struct.fieldTypes().get(i));
                }
            }
        }
        return columnDtypes.get(col);
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

    private static Map<String, Array> limitedColumns(Map<String, Array> columns, long rows) {
        var result = new LinkedHashMap<String, Array>(columns.size());
        for (var entry : columns.entrySet()) {
            result.put(entry.getKey(), Array.limited(entry.getValue(), rows));
        }
        return Map.copyOf(result);
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
                columns = limitedColumns(columns, chunkRows);
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

    /// Returns the row count of every chunk in scan order, without decoding values.
    ///
    /// Walks the file's layout tree (initialising internal state on first call) and
    /// returns one element per chunk that the iterator would yield, in the same
    /// order. Useful for tooling that needs to navigate by absolute row index
    /// (e.g. an interactive grid viewer) before deciding which chunks to actually
    /// decode.
    ///
    /// Filter pruning and `ScanOptions.limit()` are not applied — the array
    /// reflects the raw layout shape, not the projected scan.
    ///
    /// @return row counts in chunk-index order
    public long[] chunkRowCounts() {
        if (chunks == null) {
            initialize();
        }
        long[] out = new long[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            out[i] = chunks.get(i).rowCount();
        }
        return out;
    }

    /// Returns the per-zone statistics for one column, one entry per zone in scan order.
    ///
    /// When the column carries a `vortex.stats` (zone-map) layout, the rows come from that
    /// table — min/max/sum/null count per zone — decoded once from the small stats segment
    /// without touching any data segment. This is the source Rust populates for `SUM`, so the
    /// values match files written by either implementation. When no zone map is present the
    /// list falls back to each chunk's embedded `ArrayStats` (min/max/null count; `sum` is
    /// `null`, since the flat writer does not retain it). Either way a column that is absent
    /// or carries no stats yields [ArrayStats#empty()] per zone.
    ///
    /// Zone granularity is the layout's, not the scan's. The fallback path is one entry per
    /// chunk, positionally aligned with [#chunkRowCounts()]. The zone-map path is one entry per
    /// zone of the stats table: this writer emits one zone per chunk (so the same alignment
    /// holds), but a file from another writer may use a fixed zone length independent of chunk
    /// boundaries, in which case the zone count need not match [#chunkRowCounts()].
    ///
    /// This is the read-side surface for aggregate push-down (ADR 0013 §6): a reduction can
    /// fold whole zones from these rows and fall back to a streaming decode only for the
    /// boundary zones a predicate partially selects.
    ///
    /// Like [#chunkRowCounts()], filter pruning and `ScanOptions.limit()` are not applied —
    /// the list reflects the raw layout shape.
    ///
    /// @param column the column name
    /// @return per-zone stats in zone order; empty list if the file has no chunks
    public List<ArrayStats> columnZoneStats(String column) {
        if (chunks == null) {
            initialize();
        }
        List<ArrayStats> fromTable = decodeZoneTable(column);
        if (fromTable != null) {
            return fromTable;
        }
        // No zone-map table — surface each chunk's embedded ArrayStats (sum absent).
        List<ArrayStats> out = new ArrayList<>(chunks.size());
        for (ChunkSpec spec : chunks) {
            Layout flat = spec.layoutFor(column);
            out.add(flat == null ? ArrayStats.empty() : readFlatStats(flat));
        }
        return out;
    }

    /// Decodes the column's `vortex.stats` zone-map table into one [ArrayStats] per zone, or
    /// returns `null` when the column has no zone map (so the caller falls back to per-chunk
    /// node stats). The table is a single flat segment encoding a struct with a subset of the
    /// `min`/`max`/`sum`/`null_count` fields (see [ZonedStatsSchema]); it is decoded into a
    /// short-lived confined arena and the scalar values are boxed out before the arena closes.
    private List<ArrayStats> decodeZoneTable(String column) {
        Layout zoned = findZonedLayout(file.layout(), column);
        if (zoned == null || zoned.children().size() < 2) {
            return null;
        }
        Layout statsFlat = zoned.children().get(1);
        if (!statsFlat.isFlat() || statsFlat.segments().isEmpty()) {
            return null;
        }
        DType columnDtype = columnDType(column);
        if (columnDtype == null) {
            return null;
        }
        int segIdx = statsFlat.segments().getFirst();
        if (segIdx < 0 || segIdx >= file.footer().segmentSpecs().size()) {
            return null;
        }
        DType.Struct statsDtype = ZonedStatsSchema.statsTableDtype(columnDtype, zoned.metadata());
        long nZones = statsFlat.rowCount();
        SegmentSpec spec = file.footer().segmentSpecs().get(segIdx);
        try (Arena tableArena = Arena.ofConfined()) {
            Array decoded = file.decodeFlatSegment(spec, statsDtype, nZones, tableArena);
            if (!(decoded instanceof StructArray table)) {
                return null;
            }
            Array minA = fieldOrNull(table, "min");
            Array maxA = fieldOrNull(table, "max");
            Array sumA = fieldOrNull(table, "sum");
            Array nullCountA = fieldOrNull(table, "null_count");
            List<ArrayStats> out = new ArrayList<>((int) nZones);
            for (long i = 0; i < nZones; i++) {
                Object nullCount = boxedScalar(nullCountA, i);
                out.add(new ArrayStats(
                        boxedScalar(minA, i),
                        boxedScalar(maxA, i),
                        boxedScalar(sumA, i),
                        null,
                        nullCount == null ? null : ((Number) nullCount).longValue(),
                        null, null));
            }
            return out;
        }
    }

    /// Finds the first `vortex.stats` layout in the subtree of `column`'s top-level layout, or
    /// `null` when the column is not zone-mapped.
    private Layout findZonedLayout(Layout root, String column) {
        if (!(file.dtype() instanceof DType.Struct struct) || !root.isStruct()) {
            return null;
        }
        int idx = struct.fieldNames().indexOf(column);
        if (idx < 0 || idx >= root.children().size()) {
            return null;
        }
        return firstZoned(root.children().get(idx));
    }

    private static Layout firstZoned(Layout layout) {
        if (layout.isZoned()) {
            return layout;
        }
        for (Layout child : layout.children()) {
            Layout found = firstZoned(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Array fieldOrNull(StructArray table, String field) {
        if (((DType.Struct) table.dtype()).fieldNames().contains(field)) {
            return table.field(field);
        }
        return null;
    }

    /// Reads the boxed scalar at index `i` from a (possibly nullable) stats column, or `null`
    /// when the array is absent or the position is invalid.
    private static Object boxedScalar(Array array, long i) {
        if (array == null) {
            return null;
        }
        if (array instanceof MaskedArray masked) {
            if (!masked.isValid(i)) {
                return null;
            }
            return boxedScalar(masked.inner(), i);
        }
        return switch (array) {
            case LongArray a -> a.getLong(i);
            case IntArray a -> a.getInt(i);
            case DoubleArray a -> a.getDouble(i);
            case FloatArray a -> a.getFloat(i);
            case ShortArray a -> a.getShort(i);
            case ByteArray a -> a.getByte(i);
            case BoolArray a -> a.getBoolean(i);
            case VarBinArray a -> a.getString(i);
            default -> null;
        };
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
        if (sharedArena != null) {
            sharedArena.close();
            sharedArena = null;
            sharedFullArrays = null;
        }
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
        var columnTopLayouts = new LinkedHashMap<String, Layout>();
        Map<String, DType> columnDtypes = new LinkedHashMap<>();

        if (rootLayout.isStruct() && rootDtype instanceof DType.Struct structDtype) {
            List<String> projection = options.columns();
            for (int i = 0; i < rootLayout.children().size(); i++) {
                String colName = structDtype.fieldNames().get(i);
                DType colDtype = structDtype.fieldTypes().get(i);
                if (!projection.isEmpty() && !projection.contains(colName)) {
                    continue;
                }
                Layout colTop = rootLayout.children().get(i);
                var flats = new ArrayList<Layout>();
                collectFlats(colTop, flats);
                columnFlats.put(colName, flats);
                columnTopLayouts.put(colName, colTop);
                columnDtypes.put(colName, colDtype);
            }
        } else {
            var flats = new ArrayList<Layout>();
            collectFlats(rootLayout, flats);
            columnFlats.put("_col", flats);
            columnTopLayouts.put("_col", rootLayout);
            columnDtypes.put("_col", rootDtype);
        }

        projectedNames = List.copyOf(columnDtypes.keySet());
        projectedDtypes = List.copyOf(columnDtypes.values());
        chunks = buildChunks(columnFlats);
        decodeSharedColumns(columnFlats, columnTopLayouts, columnDtypes);
    }

    private void decodeSharedColumns(
            Map<String, List<Layout>> columnFlats,
            Map<String, Layout> columnTopLayouts,
            Map<String, DType> columnDtypes) {
        int maxFlats = 0;
        for (List<Layout> flats : columnFlats.values()) {
            if (flats.size() > maxFlats) {
                maxFlats = flats.size();
            }
        }
        if (maxFlats <= 1) {
            return;
        }
        for (var entry : columnFlats.entrySet()) {
            if (entry.getValue().size() != 1) {
                continue;
            }
            if (sharedArena == null) {
                sharedArena = Arena.ofConfined();
                sharedFullArrays = new java.util.HashMap<>();
            }
            String name = entry.getKey();
            Layout topLayout = columnTopLayouts.get(name);
            DType dtype = columnDtypes.get(name);
            sharedFullArrays.put(name, decodeLayout(topLayout, dtype, sharedArena));
        }
    }

    // Map.of with 1 or 2 args allocates Map1/Map2 (~2-4 fields) — avoids the
    // LinkedHashMap + Map.copyOf pair that would otherwise allocate per chunk.
    // Direct array index into ChunkSpec.columnLayouts avoids HashMap.get() per column.
    private Map<String, Array> buildColumnMap(ChunkSpec chunk, Arena arena) {
        Layout[] layouts = chunk.columnLayouts();
        long[] sliceOffsets = chunk.sliceOffsets();
        int n = projectedNames.size();
        if (n == 1) {
            Array arr = decodeOrSlice(0, layouts[0], sliceOffsets[0], chunk.rowCount(), arena);
            if (arr instanceof StructArray sa) {
                return expandStruct(sa);
            }
            return Map.of(projectedNames.getFirst(), arr);
        }
        if (n == 2) {
            return Map.of(
                    projectedNames.get(0),
                    decodeOrSlice(0, layouts[0], sliceOffsets[0], chunk.rowCount(), arena),
                    projectedNames.get(1),
                    decodeOrSlice(1, layouts[1], sliceOffsets[1], chunk.rowCount(), arena));
        }
        var scratch = new LinkedHashMap<String, Array>(n);
        for (int i = 0; i < n; i++) {
            scratch.put(projectedNames.get(i),
                    decodeOrSlice(i, layouts[i], sliceOffsets[i], chunk.rowCount(), arena));
        }
        return Map.copyOf(scratch);
    }

    private Array decodeOrSlice(int colIdx, Layout layout, long sliceStart, long rowCount,
                                Arena arena) {
        if (layout != null) {
            return decodeLayout(layout, projectedDtypes.get(colIdx), arena);
        }
        Array full = sharedFullArrays.get(projectedNames.get(colIdx));
        if (full == null) {
            throw new VortexException("scan: missing shared array for column "
                    + projectedNames.get(colIdx));
        }
        return sliceArray(full, sliceStart, rowCount, projectedDtypes.get(colIdx));
    }

    private static Array sliceArray(Array full, long offset, long length, DType dtype) {
        return switch (full) {
            case MaskedArray m -> {
                Array innerSlice = sliceArray(m.inner(), offset, length, dtype);
                BoolArray validity = m.validity();
                BoolArray validitySlice = validity == null
                        ? null
                        : (BoolArray) sliceArray(validity, offset, length, DType.BOOL);
                yield new MaskedArray(innerSlice, validitySlice);
            }
            case LongArray a -> new OffsetLongArray(dtype, length, a, offset);
            case IntArray a -> new OffsetIntArray(dtype, length, a, offset);
            case DoubleArray a -> new OffsetDoubleArray(dtype, length, a, offset);
            case FloatArray a -> new OffsetFloatArray(dtype, length, a, offset);
            case ShortArray a -> new OffsetShortArray(dtype, length, a, offset);
            case ByteArray a -> new OffsetByteArray(dtype, length, a, offset);
            case BoolArray a -> new OffsetBoolArray(dtype, length, a, offset);
            case VarBinArray a -> new VarBinArray.SlicedMode(dtype, length, a, offset);
            default -> throw new VortexException(
                    "scan: cannot slice shared array of type " + full.getClass().getSimpleName());
        };
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
        MemorySegment rawMeta = dictLayout.metadata();
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
            MemorySegment codesSeg = codes.materialize(arena);
            long bufferCodes = codesSeg.byteSize() / codesPType.byteSize();
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
        MemorySegment codesSegFallback = codes.materialize(arena);
        long bufferCodesFallback = codesSegFallback.byteSize() / codesPType.byteSize();
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
        Optional<MemorySegment> maybeSeg = codes.segmentIfPresent();
        if (maybeSeg.isEmpty()) {
            return;
        }
        long bufferCodes = maybeSeg.get().byteSize() / codesPType.byteSize();
        if (bufferCodes < n) {
            throw new VortexException(EncodingId.VORTEX_DICT,
                    "dict codes: layout row_count=" + n + " exceeds buffer capacity=" + bufferCodes);
        }
    }

    /// Builds the matching `DictXxxArray` for a primitive dictionary, unwrapping
    /// any [MaskedArray] layer on either side — dictionary lookups are keyed by code
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

    private static PType readDictLayoutCodesPType(MemorySegment rawMeta) {
        // DictLayoutMetadata (Rust): field 1 = codes_ptype, wire type 0 (varint).
        // Tag byte = (field_number << 3) | wire_type = (1 << 3) | 0 = 0x08.
        // Proto3 omits field 1 when it holds the default value (0 = U8), so empty metadata means U8.
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            return PType.U8;
        }
        byte tag = rawMeta.get(ValueLayout.JAVA_BYTE, 0);
        if (tag == 0x08 && rawMeta.byteSize() > 1) {
            int ordinal = rawMeta.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
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
                yield max != null && compareValues(max, val, columnDType(col)) <= 0;
            }
            case RowFilter.Gte(var col, var val) -> {
                Layout flat = chunk.layoutFor(col);
                if (flat == null) {
                    yield false;
                }
                Object max = readFlatStats(flat).max();
                yield max != null && compareValues(max, val, columnDType(col)) < 0;
            }
            case RowFilter.Lt(var col, var val) -> {
                Layout flat = chunk.layoutFor(col);
                if (flat == null) {
                    yield false;
                }
                Object min = readFlatStats(flat).min();
                yield min != null && compareValues(min, val, columnDType(col)) >= 0;
            }
            case RowFilter.Lte(var col, var val) -> {
                Layout flat = chunk.layoutFor(col);
                if (flat == null) {
                    yield false;
                }
                Object min = readFlatStats(flat).min();
                yield min != null && compareValues(min, val, columnDType(col)) > 0;
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
                // val < min || val > max → no row in this chunk can equal val. Route through the
                // shared comparator so this path is width-agnostic and unsigned-aware too (#159).
                DType ct = columnDType(col);
                yield compareValues(val, min, ct) < 0 || compareValues(val, max, ct) > 0;
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
                // Every row equals val (min == max == val) → no row is != val.
                DType ct = columnDType(col);
                yield compareValues(val, min, ct) == 0 && compareValues(val, max, ct) == 0;
            }
            case RowFilter.IsNull(var col) -> {
                Layout flat = chunk.layoutFor(col);
                if (flat == null) {
                    yield false;
                }
                // Zero nulls in the chunk → no row is null → nothing can match IS NULL.
                Long nullCount = readFlatStats(flat).nullCount();
                yield nullCount != null && nullCount == 0;
            }
            case RowFilter.IsNotNull(var col) -> {
                Layout flat = chunk.layoutFor(col);
                if (flat == null) {
                    yield false;
                }
                // Every row is null → no row is non-null → nothing can match IS NOT NULL.
                Long nullCount = readFlatStats(flat).nullCount();
                yield nullCount != null && nullCount == flat.rowCount();
            }
        };
    }

    private ArrayStats readFlatStats(Layout flat) {
        if (flat.segments().isEmpty()) {
            return ArrayStats.empty();
        }
        int segIdx = flat.segments().getFirst();
        if (segIdx < 0 || segIdx >= file.footer().segmentSpecs().size()) {
            return ArrayStats.empty();
        }
        SegmentSpec spec = file.footer().segmentSpecs().get(segIdx);
        long segLen = spec.length();
        // Stats are an optional zone-map pruning optimization: a malformed stats segment
        // degrades to "no stats" (empty) and never aborts the scan. This mirrors
        // VortexReader.readFlatStats — both stats readers swallow bounds errors here.
        // The trailing 4-byte fbLen lives in the segment's last bytes; reading the whole
        // segment as a ByteBuffer would fail for segments larger than 2 GB (ByteBuffer cap).
        if (segLen < 4) {
            return ArrayStats.empty();
        }
        MemorySegment seg = file.rawSegment(spec);
        int fbLen = seg.get(LE_INT, segLen - 4);
        if (fbLen < 0 || fbLen > segLen - 4) {
            return ArrayStats.empty();
        }
        long fbStart = segLen - 4L - fbLen;
        var fbArray = io.github.dfa1.vortex.core.fbs.FbsArray.getRootAsFbsArray(IoBounds.slice(seg, fbStart, fbLen));

        io.github.dfa1.vortex.core.fbs.FbsArrayNode root = fbArray.root();
        if (root == null) {
            return ArrayStats.empty();
        }
        return ArrayStats.fromFbs(root.stats());
    }

    // ── Internal record ───────────────────────────────────────────────────────

    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record ChunkSpec(
            long rowCount, String[] columnNames, Layout[] columnLayouts, long[] sliceOffsets) {
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
