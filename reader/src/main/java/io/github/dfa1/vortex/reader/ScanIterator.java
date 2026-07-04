package io.github.dfa1.vortex.reader;

import static io.github.dfa1.vortex.core.io.PTypeIO.LE_INT;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.compute.Compare;
import io.github.dfa1.vortex.reader.compute.Predicate;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
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
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.reader.layout.LayoutDecodeContext;
import io.github.dfa1.vortex.reader.layout.ZonedStatsSchema;

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
    private Map<String, Layout> columnTopLayouts;
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
            Chunk chunk = new Chunk(chunkRows, columns, projectedDtypeMap(), arena, this::onChunkClosed);
            openChunk = chunk;
            return chunk;
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /// Returns the number of chunks in the file's layout, ignoring filter pruning and
    /// `ScanOptions.limit()`. Equal to the length of [#chunkRowCounts()].
    ///
    /// @return chunk count for the projected columns
    public int chunkCount() {
        if (chunks == null) {
            initialize();
        }
        return chunks.size();
    }

    /// Decodes exactly the chunk at `chunkIndex` for the projected columns into a fresh
    /// confined [Arena] owned by the returned [Chunk].
    ///
    /// Unlike [#next()] this is random access and does not advance the iterator: it ignores
    /// filter pruning and `ScanOptions.limit()`, decoding the chunk exactly as the raw layout
    /// shape describes it. The result is also independent of this iterator's lifetime — a
    /// shared (non-chunked) column is decoded into the chunk's own arena rather than sliced
    /// from the iterator's shared arena, so the [Chunk] stays valid after this iterator is
    /// closed (until the chunk itself is closed).
    ///
    /// @param chunkIndex zero-based chunk index, in `[0, chunkCount())`
    /// @return a self-contained [Chunk] for that chunk's projected columns
    /// @throws VortexException if `chunkIndex` is out of bounds
    Chunk decodeChunkAt(int chunkIndex) {
        if (chunks == null) {
            initialize();
        }
        if (chunkIndex < 0 || chunkIndex >= chunks.size()) {
            throw new VortexException("decodeChunk: chunk index " + chunkIndex
                    + " out of bounds [0, " + chunks.size() + ")");
        }
        ChunkSpec spec = chunks.get(chunkIndex);
        Arena arena = Arena.ofConfined();
        try {
            Map<String, Array> columns = buildSelfContainedColumnMap(spec, arena);
            return new Chunk(spec.rowCount(), columns, projectedDtypeMap(), arena, c -> { });
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    private Map<String, DType> projectedDtypeMap() {
        Map<String, DType> map = new LinkedHashMap<>(projectedNames.size());
        for (int i = 0; i < projectedNames.size(); i++) {
            map.put(projectedNames.get(i), projectedDtypes.get(i));
        }
        return map;
    }

    /// Returns the row count of every chunk in scan order, without decoding values.
    ///
    /// Walks the file's layout tree (initializing internal state on first call) and
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
        this.columnTopLayouts = Map.copyOf(columnTopLayouts);
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

    /// Builds the column map for [#decodeChunkAt(int)]. Identical decode to [#buildColumnMap]
    /// except that a shared (single-flat) column is decoded into `arena` and sliced there,
    /// so the resulting [Chunk] owns every buffer and survives this iterator's close.
    private Map<String, Array> buildSelfContainedColumnMap(ChunkSpec chunk, Arena arena) {
        Layout[] layouts = chunk.columnLayouts();
        long[] sliceOffsets = chunk.sliceOffsets();
        int n = projectedNames.size();
        if (n == 1) {
            Array arr = decodeOrSliceSelfContained(0, layouts[0], sliceOffsets[0], chunk.rowCount(), arena);
            if (arr instanceof StructArray sa) {
                return expandStruct(sa);
            }
            return Map.of(projectedNames.getFirst(), arr);
        }
        var scratch = new LinkedHashMap<String, Array>(n);
        for (int i = 0; i < n; i++) {
            scratch.put(projectedNames.get(i),
                    decodeOrSliceSelfContained(i, layouts[i], sliceOffsets[i], chunk.rowCount(), arena));
        }
        return Map.copyOf(scratch);
    }

    private Array decodeOrSliceSelfContained(int colIdx, Layout layout, long sliceStart,
                                             long rowCount, Arena arena) {
        if (layout != null) {
            return decodeLayout(layout, projectedDtypes.get(colIdx), arena);
        }
        // Shared single-flat column: decode its full top layout into THIS chunk's arena and
        // slice, so the returned Chunk does not reference the iterator's shared arena.
        String name = projectedNames.get(colIdx);
        DType dtype = projectedDtypes.get(colIdx);
        Array full = decodeLayout(columnTopLayouts.get(name), dtype, arena);
        return sliceArray(full, sliceStart, rowCount, dtype);
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
        return file.layoutRegistry().decode(new ScanLayoutContext(file, arena), layout, dtype);
    }

    // ── Limit truncation ─────────────────────────────────────────────────────

    private boolean canPruneChunk(ChunkSpec chunk, RowFilter filter) {
        return switch (filter) {
            case RowFilter.And(var filters) -> {
                // Conjunction: pruning any one conjunct prunes the whole chunk (short-circuit).
                for (RowFilter f : filters) {
                    if (canPruneChunk(chunk, f)) {
                        yield true;
                    }
                }
                yield false;
            }
            case RowFilter.Column(var col, var predicate) -> {
                Layout flat = chunk.layoutFor(col);
                if (flat == null) {
                    yield false;
                }
                ArrayStats stats = readFlatStats(flat);
                yield canPrune(predicate, stats, flat.rowCount(), columnDType(col));
            }
        };
    }

    /// Tests whether `predicate`, compiled against a chunk's zone-map statistics, can prove that no
    /// row in the chunk can match — in which case the chunk is skipped. Pruning is strictly
    /// conservative: every branch returns `false` (do not prune) when a needed statistic is missing
    /// or the outcome is uncertain, so a chunk is skipped only when it provably holds no match. All
    /// ordering routes through [Compare#values(Object, Object, DType)] so the test stays
    /// width-agnostic and unsigned/float-aware, identically to the row-level scan it gates.
    ///
    /// @param predicate the value-test bound to the column
    /// @param stats     the column's per-chunk min/max/nullCount statistics
    /// @param rowCount  the chunk's row count
    /// @param ct        the column's dtype, deciding the comparison mode
    /// @return `true` if no row can match and the chunk may be pruned
    private static boolean canPrune(Predicate predicate, ArrayStats stats, long rowCount, DType ct) {
        Object min = stats.min();
        Object max = stats.max();
        Long nullCount = stats.nullCount();
        return switch (predicate) {
            // val < min || val > max → no row equals val.
            case Predicate.Eq(var v) -> min != null && max != null
                    && (Compare.values(v, min, ct) < 0 || Compare.values(v, max, ct) > 0);
            // Every row equals val (min == max == val) and no nulls → no row differs. A null row is
            // neither = nor != val under three-valued logic, so require a provably null-free chunk.
            case Predicate.Neq(var v) -> min != null && max != null
                    && nullCount != null && nullCount == 0
                    && Compare.values(v, min, ct) == 0 && Compare.values(v, max, ct) == 0;
            // max <= val → no row is > val.
            case Predicate.Gt(var v) -> max != null && Compare.values(max, v, ct) <= 0;
            // max < val → no row is >= val.
            case Predicate.Gte(var v) -> max != null && Compare.values(max, v, ct) < 0;
            // min >= val → no row is < val.
            case Predicate.Lt(var v) -> min != null && Compare.values(min, v, ct) >= 0;
            // min > val → no row is <= val.
            case Predicate.Lte(var v) -> min != null && Compare.values(min, v, ct) > 0;
            // hi < min || lo > max → the range and the chunk's span are disjoint.
            case Predicate.Between(var lo, var hi) -> min != null && max != null
                    && (Compare.values(hi, min, ct) < 0 || Compare.values(lo, max, ct) > 0);
            // Zero nulls → no row is null → nothing can match IS NULL.
            case Predicate.IsNull ignored -> nullCount != null && nullCount == 0;
            // Every row is null → no row is non-null → nothing can match IS NOT NULL.
            case Predicate.IsNotNull ignored -> nullCount != null && nullCount == rowCount;
            // A conjunction is unsatisfiable if either side is; a disjunction only if both are.
            case Predicate.And(var left, var right) ->
                    canPrune(left, stats, rowCount, ct) || canPrune(right, stats, rowCount, ct);
            case Predicate.Or(var left, var right) ->
                    canPrune(left, stats, rowCount, ct) && canPrune(right, stats, rowCount, ct);
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

    // ── Layout decode context ─────────────────────────────────────────────────

    /// Binds a [LayoutDecodeContext] to one decode epoch (one arena). Recursion into children
    /// routes back through the file's [LayoutRegistry] with the same arena, so nested layouts
    /// land in the chunk the scan is currently filling.
    private record ScanLayoutContext(VortexHandle file, SegmentAllocator arena)
            implements LayoutDecodeContext {

        @Override
        public Array decodeChild(Layout child, DType dtype) {
            return file.layoutRegistry().decode(this, child, dtype);
        }

        @Override
        public Array decodeFlatSegment(SegmentSpec spec, DType dtype, long rowCount) {
            return file.decodeFlatSegment(spec, dtype, rowCount, arena);
        }

        @Override
        public SegmentSpec segmentSpec(int index) {
            return file.footer().segmentSpecs().get(index);
        }
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
