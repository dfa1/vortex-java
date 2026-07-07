package io.github.dfa1.vortex.reader;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.SequencedMap;
import java.util.TreeSet;
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
    private List<ColumnName> projectedNames;
    private List<DType> projectedDtypes;
    private Map<ColumnName, DType> columnDtypes;
    private int chunkIndex;
    private int peekedChunkIdx = -1;
    private long rowsReturned;
    private Chunk openChunk;
    private boolean closed;
    // Coarse chunks that span several split windows are decoded once and cached here (keyed by
    // layout identity), each in its own confined arena so it can be released the moment the scan
    // advances past its last covering window — see evictPassedFlats.
    private Map<Layout, CachedFlat> sharedFlats;
    // Per projected column, the covering flat of the most recently decoded window; drives eviction.
    private Layout[] lastCoveringFlats;

    /// A coarse covering flat decoded once and sliced across the windows it spans, together with
    /// the confined [Arena] that owns its buffers (closed on eviction / iterator close).
    ///
    /// @param arena the confined arena owning this flat's decoded buffers
    /// @param array the decoded flat, sliced per window
    private record CachedFlat(Arena arena, Array array) {
    }

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
        } else if (layout.isStruct()) {
            // A nested struct column (a vortex.struct under the root struct) spans the same full
            // row range as its parent — its per-field children are separate physical columns, not
            // row chunks. Treat the whole subtree as one chunk source (like a dict leaf) so chunk
            // planning sees a single full-range chunk; decode routes through the registry's
            // StructLayoutDecoder, which reassembles a StructArray from the field children.
            out.add(layout);
        }
    }

    /// Plans the scan by merging every column's chunk grid into one split grid, mirroring the Rust
    /// reference: `StructReader::register_splits` unions each field's chunk boundaries into a single
    /// sorted, deduplicated set (`vortex-layout/src/scan/split_by.rs`), and the scan then walks each
    /// adjacent-boundary window slicing every column's covering chunk to it. The merged grid refines
    /// every column's grid, so each window lies entirely within exactly one chunk of each column.
    ///
    /// This subsumes the previous special cases without regressing them:
    /// - aligned N-vs-N (all columns share a grid) — every window equals a whole chunk, so decode is
    ///   direct with no slicing;
    /// - 1-vs-N (one full-column flat over a chunked column) — the single flat covers every window
    ///   and is decoded once, then sliced;
    /// - nested boundaries (emotions-dataset-for-nlp: `label` `[131072 ×3, 23593]` vs `text`'s
    ///   26-chunk `16384` grid) and disjoint grids (uci-beijing-multi-site-air-quality: numeric
    ///   `[131072 ×3, 27552]` vs `station`'s `40960/49152/...` grid) both fall out of the union.
    static List<ChunkSpec> buildChunks(Map<ColumnName, List<Layout>> columnFlats) {
        if (columnFlats.isEmpty()) {
            return List.of();
        }
        ColumnName[] colNames = columnFlats.keySet().toArray(ColumnName[]::new);
        int numCols = colNames.length;

        // Per-column cumulative chunk starts: colStarts[j][c] is the first row of chunk c and the
        // final entry is the column's total row count. Chunk c spans [colStarts[j][c], colStarts[j][c+1]).
        long[][] colStarts = new long[numCols][];
        for (int j = 0; j < numCols; j++) {
            List<Layout> flats = columnFlats.get(colNames[j]);
            long[] starts = new long[flats.size() + 1];
            long acc = 0;
            for (int c = 0; c < flats.size(); c++) {
                starts[c] = acc;
                acc += flats.get(c).rowCount();
            }
            starts[flats.size()] = acc;
            colStarts[j] = starts;
        }

        long[] boundaries = mergedBoundaries(colStarts);
        int numWindows = boundaries.length - 1;
        var result = new ArrayList<ChunkSpec>(numWindows);
        int[] cursor = new int[numCols]; // covering chunk index per column; advances monotonically
        for (int w = 0; w < numWindows; w++) {
            long windowStart = boundaries[w];
            long windowRows = boundaries[w + 1] - windowStart;
            Layout[] layouts = new Layout[numCols];
            long[] sliceOffsets = new long[numCols];
            for (int j = 0; j < numCols; j++) {
                long[] starts = colStarts[j];
                int c = cursor[j];
                // Advance to the chunk whose range contains windowStart (starts[c+1] > windowStart).
                while (c + 1 < starts.length && starts[c + 1] <= windowStart) {
                    c++;
                }
                cursor[j] = c;
                List<Layout> flats = columnFlats.get(colNames[j]);
                if (c >= flats.size()) {
                    throw new VortexException("scan: column '" + colNames[j]
                            + "' has no chunk covering rows [" + windowStart + ", "
                            + (windowStart + windowRows) + ")");
                }
                layouts[j] = flats.get(c);
                sliceOffsets[j] = windowStart - starts[c];
            }
            result.add(new ChunkSpec(windowRows, colNames, layouts, sliceOffsets));
        }
        return List.copyOf(result);
    }

    /// Returns the sorted, deduplicated union of every column's cumulative chunk starts (each ending
    /// in the column's total row count). The result always contains `0` and the total; adjacent
    /// entries are the scan's split windows.
    private static long[] mergedBoundaries(long[][] colStarts) {
        var set = new TreeSet<Long>();
        for (long[] starts : colStarts) {
            for (long s : starts) {
                set.add(s);
            }
        }
        long[] out = new long[set.size()];
        int i = 0;
        for (long v : set) {
            out[i++] = v;
        }
        return out;
    }

    // ── Layout tree traversal ─────────────────────────────────────────────────

    /// Returns the declared [DType] of column `col`, or `null` if the file is not a struct or has
    /// no such column. Resolved once from the file's struct schema and cached; used to drive
    /// zone-map comparisons by the column's true type rather than the filter value's boxing.
    private DType columnDType(ColumnName col) {
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

    private static SequencedMap<ColumnName, Chunk.Column> expandStruct(StructArray sa) {
        DType.Struct sd = (DType.Struct) sa.dtype();
        List<ColumnName> names = sd.fieldNames();
        List<DType> types = sd.fieldTypes();
        int n = names.size();
        var map = new LinkedHashMap<ColumnName, Chunk.Column>(n);
        for (int i = 0; i < n; i++) {
            map.put(names.get(i), new Chunk.Column(sa.field(i), types.get(i)));
        }
        return unmodifiable(map);
    }

    // ── Zone-map pruning ──────────────────────────────────────────────────────

    private static SequencedMap<ColumnName, Chunk.Column> limitedColumns(
            SequencedMap<ColumnName, Chunk.Column> columns, long rows) {
        var result = new LinkedHashMap<ColumnName, Chunk.Column>(columns.size());
        for (var entry : columns.entrySet()) {
            Chunk.Column col = entry.getValue();
            result.put(entry.getKey(), new Chunk.Column(Array.limited(col.array(), rows), col.dtype()));
        }
        return unmodifiable(result);
    }

    private static SequencedMap<ColumnName, Chunk.Column> unmodifiable(
            SequencedMap<ColumnName, Chunk.Column> map) {
        return Collections.unmodifiableSequencedMap(map);
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

        evictPassedFlats(spec);

        long remaining = options.limit() - rowsReturned;
        long chunkRows = Math.min(spec.rowCount(), remaining);

        Arena arena = Arena.ofConfined();
        try {
            SequencedMap<ColumnName, Chunk.Column> columns = buildColumnMap(spec, arena);
            if (chunkRows < spec.rowCount()) {
                columns = limitedColumns(columns, chunkRows);
            }
            rowsReturned += chunkRows;
            Chunk chunk = new Chunk(chunkRows, columns, arena, this::onChunkClosed);
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
            SequencedMap<ColumnName, Chunk.Column> columns = buildSelfContainedColumnMap(spec, arena);
            return new Chunk(spec.rowCount(), columns, arena, c -> { });
        } catch (RuntimeException e) {
            arena.close();
            throw e;
        }
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
    /// Zone granularity is the layout's, not the scan's. The fallback path is one entry per scan
    /// window, positionally aligned with [#chunkRowCounts()]. Each entry carries the stats of the
    /// covering chunk of that window, so when columns chunk on different grids a coarse chunk's
    /// stats repeat across every sub-window it spans (still a conservative min/max/null-count bound
    /// for each sub-window). The zone-map path is one entry per zone of the stats table: this writer
    /// emits one zone per chunk (so the same alignment holds), but a file from another writer may
    /// use a fixed zone length independent of chunk boundaries, in which case the zone count need
    /// not match [#chunkRowCounts()].
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
        ColumnName name = ColumnName.of(column);
        List<ArrayStats> fromTable = decodeZoneTable(name);
        if (fromTable != null) {
            return fromTable;
        }
        // No zone-map table — surface each chunk's embedded ArrayStats (sum absent).
        List<ArrayStats> out = new ArrayList<>(chunks.size());
        for (ChunkSpec spec : chunks) {
            Layout flat = spec.layoutFor(name);
            out.add(flat == null ? ArrayStats.empty() : readFlatStats(flat));
        }
        return out;
    }

    /// Decodes the column's zone-map table into one [ArrayStats] per zone, or returns `null` when
    /// the column has no zone map (so the caller falls back to per-chunk node stats). The table is
    /// a single flat segment encoding a struct with a subset of the `min`/`max`/`sum`/`null_count`
    /// fields; the schema is reconstructed from the layout metadata (legacy `vortex.stats` bitset
    /// or newer `vortex.zoned` aggregate specs — see [ZonedStatsSchema]). It is decoded into a
    /// short-lived confined arena and the scalar values are boxed out before the arena closes.
    private List<ArrayStats> decodeZoneTable(ColumnName column) {
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
        // The canonical `vortex.zoned` id (Rust >= 0.76) stores an aggregate-spec metadata blob;
        // the legacy `vortex.stats` alias (what vortex-java writes) stores a Stat bitset. They
        // reconstruct the table schema differently — dispatch on the layout id.
        DType.Struct statsDtype = zoned.layoutId() == LayoutId.ZONED
                ? ZonedStatsSchema.aggregateStatsTableDtype(columnDtype, zoned.metadata())
                : ZonedStatsSchema.statsTableDtype(columnDtype, zoned.metadata());
        if (statsDtype == null || statsDtype.fieldNames().isEmpty()) {
            return null;
        }
        long nZones = statsFlat.rowCount();
        SegmentSpec spec = file.footer().segmentSpecs().get(segIdx);
        try (Arena tableArena = Arena.ofConfined()) {
            Array decoded = file.decodeSegment(spec, statsDtype, nZones, tableArena);
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
    private Layout findZonedLayout(Layout root, ColumnName column) {
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
        if (((DType.Struct) table.dtype()).fieldNames().contains(ColumnName.of(field))) {
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
        if (sharedFlats != null) {
            for (CachedFlat cached : sharedFlats.values()) {
                cached.arena().close();
            }
            sharedFlats = null;
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

        var columnFlats = new LinkedHashMap<ColumnName, List<Layout>>();
        Map<ColumnName, DType> columnDtypes = new LinkedHashMap<>();

        if (rootLayout.isStruct() && rootDtype instanceof DType.Struct structDtype) {
            List<ColumnName> projection = options.columns();
            for (int i = 0; i < rootLayout.children().size(); i++) {
                ColumnName colName = structDtype.fieldNames().get(i);
                DType colDtype = structDtype.fieldTypes().get(i);
                if (!projection.isEmpty() && !projection.contains(colName)) {
                    continue;
                }
                Layout colTop = rootLayout.children().get(i);
                var flats = new ArrayList<Layout>();
                collectFlats(colTop, flats);
                columnFlats.put(colName, flats);
                columnDtypes.put(colName, colDtype);
            }
        } else {
            var flats = new ArrayList<Layout>();
            collectFlats(rootLayout, flats);
            ColumnName colName = ColumnName.of("_col");
            columnFlats.put(colName, flats);
            columnDtypes.put(colName, rootDtype);
        }

        projectedNames = List.copyOf(columnDtypes.keySet());
        projectedDtypes = List.copyOf(columnDtypes.values());
        lastCoveringFlats = new Layout[projectedNames.size()];
        chunks = buildChunks(columnFlats);
    }

    // A LinkedHashMap preserves schema/projection order (the public columns() contract is a
    // SequencedMap). Direct array index into ChunkSpec.columnLayouts avoids HashMap.get() per column.
    private SequencedMap<ColumnName, Chunk.Column> buildColumnMap(ChunkSpec chunk, Arena arena) {
        Layout[] layouts = chunk.columnLayouts();
        long[] sliceOffsets = chunk.sliceOffsets();
        int n = projectedNames.size();
        if (n == 1) {
            Array arr = decodeOrSlice(0, layouts[0], sliceOffsets[0], chunk.rowCount(), arena);
            if (arr instanceof StructArray sa) {
                return expandStruct(sa);
            }
            return singleColumn(arr);
        }
        var scratch = new LinkedHashMap<ColumnName, Chunk.Column>(n);
        for (int i = 0; i < n; i++) {
            scratch.put(projectedNames.get(i), new Chunk.Column(
                    decodeOrSlice(i, layouts[i], sliceOffsets[i], chunk.rowCount(), arena),
                    projectedDtypes.get(i)));
        }
        return unmodifiable(scratch);
    }

    /// Builds the column map for [#decodeChunkAt(int)]. Identical decode to [#buildColumnMap]
    /// except that a covering chunk spanning several windows is decoded into `arena` and sliced
    /// there, so the resulting [Chunk] owns every buffer and survives this iterator's close.
    private SequencedMap<ColumnName, Chunk.Column> buildSelfContainedColumnMap(ChunkSpec chunk, Arena arena) {
        Layout[] layouts = chunk.columnLayouts();
        long[] sliceOffsets = chunk.sliceOffsets();
        int n = projectedNames.size();
        if (n == 1) {
            Array arr = decodeOrSliceSelfContained(0, layouts[0], sliceOffsets[0], chunk.rowCount(), arena);
            if (arr instanceof StructArray sa) {
                return expandStruct(sa);
            }
            return singleColumn(arr);
        }
        var scratch = new LinkedHashMap<ColumnName, Chunk.Column>(n);
        for (int i = 0; i < n; i++) {
            scratch.put(projectedNames.get(i), new Chunk.Column(
                    decodeOrSliceSelfContained(i, layouts[i], sliceOffsets[i], chunk.rowCount(), arena),
                    projectedDtypes.get(i)));
        }
        return unmodifiable(scratch);
    }

    private SequencedMap<ColumnName, Chunk.Column> singleColumn(Array array) {
        var map = new LinkedHashMap<ColumnName, Chunk.Column>(1);
        map.put(projectedNames.getFirst(), new Chunk.Column(array, projectedDtypes.getFirst()));
        return unmodifiable(map);
    }

    private Array decodeOrSliceSelfContained(int colIdx, Layout coveringFlat, long sliceOffset,
                                             long rowCount, Arena arena) {
        DType dtype = projectedDtypes.get(colIdx);
        Array full = decodeLayout(coveringFlat, dtype, arena);
        if (isWholeFlat(coveringFlat, sliceOffset, rowCount)) {
            return full;
        }
        // Covering chunk spans several windows: slice it into THIS chunk's arena so the returned
        // Chunk owns every buffer and survives this iterator's close.
        return sliceArray(full, sliceOffset, rowCount, dtype);
    }

    private Array decodeOrSlice(int colIdx, Layout coveringFlat, long sliceOffset, long rowCount,
                                Arena arena) {
        DType dtype = projectedDtypes.get(colIdx);
        if (isWholeFlat(coveringFlat, sliceOffset, rowCount)) {
            // Fast path: the window is a whole chunk — decode straight into the chunk's arena with
            // no slice wrapper (aligned N-vs-N and per-chunk decode keep their zero-copy behavior).
            return decodeLayout(coveringFlat, dtype, arena);
        }
        // Covering chunk spans several windows: decode it once (cached) and slice zero-copy per
        // window, matching how Rust decodes a chunk once and slices each split range.
        Array full = sharedCoveringFlat(coveringFlat, dtype);
        return sliceArray(full, sliceOffset, rowCount, dtype);
    }

    private static boolean isWholeFlat(Layout coveringFlat, long sliceOffset, long rowCount) {
        return sliceOffset == 0 && rowCount == coveringFlat.rowCount();
    }

    /// Decodes `flat` once into its own confined arena and caches it by identity, so a coarse chunk
    /// that covers several split windows is decoded a single time and then sliced per window. The
    /// arena is released by [#evictPassedFlats] once the scan advances off the flat.
    private Array sharedCoveringFlat(Layout flat, DType dtype) {
        if (sharedFlats == null) {
            sharedFlats = new IdentityHashMap<>();
        }
        return sharedFlats.computeIfAbsent(flat, f -> {
            Arena flatArena = Arena.ofConfined();
            return new CachedFlat(flatArena, decodeLayout(f, dtype, flatArena));
        }).array();
    }

    /// Releases every cached coarse flat the scan has moved past. As `spec` is decoded, a column
    /// whose covering flat differs from the one it used in the previously decoded window can never
    /// revisit that earlier flat — the planner's per-column cursor advances monotonically — so its
    /// arena is closed and its cache entry dropped. Whole-window (uncached) flats are ignored. The
    /// previously open [Chunk] is already closed here (enforced by [#next()]), so no live slice
    /// still references the freed arena.
    private void evictPassedFlats(ChunkSpec spec) {
        Layout[] current = spec.columnLayouts();
        for (int j = 0; j < current.length; j++) {
            Layout previous = lastCoveringFlats[j];
            if (previous != null && previous != current[j]) {
                CachedFlat cached = sharedFlats == null ? null : sharedFlats.remove(previous);
                if (cached != null) {
                    cached.arena().close();
                }
            }
            lastCoveringFlats[j] = current[j];
        }
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
            case StructArray s -> {
                // A shared nested struct column is decoded once over the full range, then sliced
                // per chunk by slicing each field into the same window. Field dtypes come from the
                // struct dtype so each field slices with its own offset-array type.
                DType.Struct sd = (DType.Struct) dtype;
                var slicedFields = new ArrayList<Array>(s.fieldCount());
                for (int i = 0; i < s.fieldCount(); i++) {
                    slicedFields.add(sliceArray(s.field(i), offset, length, sd.fieldTypes().get(i)));
                }
                yield new StructArray(sd, length, slicedFields);
            }
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
                // A valid-but-absent name yields no layout (no pruning), matching the row-level
                // scan it gates.
                ColumnName name = col;
                Layout flat = chunk.layoutFor(name);
                if (flat == null) {
                    yield false;
                }
                ArrayStats stats = readFlatStats(flat);
                yield canPrune(predicate, stats, flat.rowCount(), columnDType(name));
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
            case Predicate.IsNull _ -> nullCount != null && nullCount == 0;
            // Every row is null → no row is non-null → nothing can match IS NOT NULL.
            case Predicate.IsNotNull _ -> nullCount != null && nullCount == rowCount;
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
        public Array decodeSegment(SegmentSpec spec, DType dtype, long rowCount) {
            return file.decodeSegment(spec, dtype, rowCount, arena);
        }

        @Override
        public SegmentSpec segmentSpec(int index) {
            List<SegmentSpec> specs = file.footer().segmentSpecs();
            if (index < 0 || index >= specs.size()) {
                // Untrusted input: a malformed flat layout may carry any segment index.
                throw new VortexException("segment index " + index
                        + " out of bounds (segmentSpecs.size=" + specs.size() + ")");
            }
            return specs.get(index);
        }
    }

    // ── Internal record ───────────────────────────────────────────────────────

    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    record ChunkSpec(
            long rowCount, ColumnName[] columnNames, Layout[] columnLayouts, long[] sliceOffsets) {
        Layout layoutFor(ColumnName col) {
            for (int i = 0; i < columnNames.length; i++) {
                if (columnNames[i].equals(col)) {
                    return columnLayouts[i];
                }
            }
            return null;
        }
    }
}
