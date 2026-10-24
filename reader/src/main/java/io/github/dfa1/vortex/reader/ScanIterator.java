package io.github.dfa1.vortex.reader;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.compute.Compare;
import io.github.dfa1.vortex.reader.compute.Compute;
import io.github.dfa1.vortex.reader.compute.Predicate;
import io.github.dfa1.vortex.reader.compute.RowSelection;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.CanonicalArrays;
import io.github.dfa1.vortex.reader.array.DictIntArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.reader.array.MapArray;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.array.LazyConstantDecimalArray;
import io.github.dfa1.vortex.reader.array.LazyDecimalArray;
import io.github.dfa1.vortex.reader.array.LazyDecimalBytePartsArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.OffsetBoolArray;
import io.github.dfa1.vortex.reader.array.OffsetByteArray;
import io.github.dfa1.vortex.reader.array.OffsetDoubleArray;
import io.github.dfa1.vortex.reader.array.OffsetFloatArray;
import io.github.dfa1.vortex.reader.array.OffsetIntArray;
import io.github.dfa1.vortex.reader.array.OffsetLongArray;
import io.github.dfa1.vortex.reader.array.OffsetShortArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.UnionArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinSlicedArray;
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.reader.layout.LayoutDecodeContext;
import io.github.dfa1.vortex.reader.layout.ZonedStatsSchema;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

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
    // the options' limit, with no limit as Long.MAX_VALUE so the row arithmetic needs no branch
    private final long limit;
    // the options' filter, or null for none
    private final ScanFilter filter;

    private List<ChunkSpec> chunks;
    // The columns each returned chunk exposes: the projection, without the columns decoded only to
    // test the filter.
    private List<ColumnName> outputNames;
    private Map<ColumnName, Integer> outputPositions;
    // A chunk hasNext() decoded to test the filter on, and its matching rows, until next() returns
    // them: a chunk with no match is skipped, as Rust's scan returns no empty batch.
    private Chunk pending;
    private long[] pendingRows;
    private List<ColumnName> projectedNames;
    private List<DType> projectedDtypes;
    private Map<ColumnName, Integer> projectedPositions;
    // True only when the file's root layout isn't itself a Struct layout (no per-column layout
    // children — e.g. a single flat/chunked segment covering the whole file, as produced by some
    // older writers), so #initialize() fell back to one synthetic "_col" placeholder covering the
    // whole file. Gates #expandStruct(StructArray): that placeholder's decoded array may itself be
    // a multi-field StructArray standing in for the file's real columns, which must be unpacked by
    // field name. A *legitimately* single-column projection whose one real column happens to be
    // Struct-typed (dtype.List/Struct nesting) must NOT be expanded the same way — it already has
    // its own name and dtype from the schema, unlike the synthetic placeholder.
    private boolean singleColumnIsSyntheticWrapper;
    private Map<ColumnName, DType> columnDtypes;
    // Decoded zone-map table per column, fetched at most once per scan (one segment read instead of
    // one per pruning check) — see #zonesFor(ColumnName, boolean). Absent entries (no usable zone map) are
    // not cached: those paths cost no I/O, so recomputing is cheap.
    private Map<ColumnName, List<Zone>> zoneCache;
    // Same zones with Rust's bounded string min/max filled in: pruning only, never surfaced.
    private Map<ColumnName, List<Zone>> pruningZoneCache;
    // Per dictionary layout and predicate, whether none of its values matches: decided once per scan.
    private Map<Layout, Map<Predicate, Boolean>> dictPruneCache;
    // Per column, the decoded values of the latest dictionary the pruning checks read.
    private Map<ColumnName, DictValues> dictValuesByColumn;
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

    ScanIterator(VortexHandle file, ScanOptions options) {
        this.file = file;
        this.options = options;
        this.limit = options.limit().map(ScanLimit::rows).orElse(Long.MAX_VALUE);
        this.filter = options.filter().orElse(null);
    }

    /// Index of a chunked layout's first data child: `metadata[0] == 1` means `children[0]` is the
    /// per-chunk stats layout rather than a row chunk.
    private static int chunkedDataStart(Layout chunked) {
        boolean statsChild = chunked.metadata() != null
                && chunked.metadata().byteSize() > 0
                && chunked.metadata().get(ValueLayout.JAVA_BYTE, 0) == 1;
        return statsChild ? 1 : 0;
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
            for (int i = chunkedDataStart(layout); i < layout.children().size(); i++) {
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
            result.add(new ChunkSpec(windowStart, windowRows, colNames, layouts, sliceOffsets));
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
        var map = LinkedHashMap.<ColumnName, Chunk.Column>newLinkedHashMap(n);
        for (int i = 0; i < n; i++) {
            map.put(names.get(i), new Chunk.Column(sa.field(i), types.get(i)));
        }
        return unmodifiable(map);
    }

    // ── Zone-map pruning ──────────────────────────────────────────────────────

    private static SequencedMap<ColumnName, Chunk.Column> limitedColumns(
            SequencedMap<ColumnName, Chunk.Column> columns, long rows) {
        var result = LinkedHashMap.<ColumnName, Chunk.Column>newLinkedHashMap(columns.size());
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
        if (rowsReturned >= limit) {
            return false;
        }
        while (chunkIndex < chunks.size()) {
            ChunkSpec spec = chunks.get(chunkIndex);
            if (filter != null && (canPruneChunk(spec, filter) || !selectMatchingRows(spec))) {
                chunkIndex++;
                continue;
            }
            peekedChunkIdx = chunkIndex;
            return true;
        }
        return false;
    }

    /// Decodes `spec` and keeps the rows the filter selects, up to the rows the limit still
    /// allows, for [#next()] to return: the filter is exact, as in Rust, so the limit counts rows
    /// after filtering.
    ///
    /// @return `false`, and nothing kept, when no row of `spec` matches
    private boolean selectMatchingRows(ChunkSpec spec) {
        // an open chunk may still read the flats eviction would free; next() evicts once it is closed
        if (openChunk == null || openChunk.isClosed()) {
            evictPassedFlats(spec);
        }
        Chunk full = chunkFor(spec, spec.rowCount());
        try {
            RowSelection selection = Compute.matcher(full, filter);
            long[] rows = new long[(int) Math.min(spec.rowCount(), limit - rowsReturned)];
            int count = 0;
            for (long row = selection.nextSelected(0); row >= 0 && count < rows.length;
                    row = selection.nextSelected(row + 1)) {
                rows[count++] = row;
            }
            if (count == 0) {
                full.close();
                return false;
            }
            pending = full;
            pendingRows = count == rows.length ? rows : Arrays.copyOf(rows, count);
            return true;
        } catch (RuntimeException e) {
            full.close();
            throw e;
        }
    }

    /// Whether the filter's zone statistics or dictionary values skip chunk `index` without
    /// decoding it: the pruning half of a filtered scan, ahead of its row test. Like
    /// [#chunkRowCounts()], it describes the scan's plan and ignores the limit.
    ///
    /// @param index the chunk, in `[0, chunkCount())`
    /// @return `true` when chunk `index` holds no row the filter can select; `false` with no filter
    public boolean isChunkPruned(int index) {
        if (chunks == null) {
            initialize();
        }
        return filter != null && canPruneChunk(chunks.get(index), filter);
    }

    private static void collectColumns(ScanFilter filter, Set<ColumnName> out) {
        switch (filter) {
            case ScanFilter.And(var parts) -> parts.forEach(part -> collectColumns(part, out));
            case ScanFilter.Column(var col, var _) -> out.add(col);
        }
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

        Chunk chunk;
        if (pending != null) {
            chunk = pending;
            long[] rows = pendingRows;
            pending = null;
            pendingRows = null;
            chunk = selectedChunk(chunk, rows);
            rowsReturned += rows.length;
        } else {
            long chunkRows = Math.min(spec.rowCount(), limit - rowsReturned);
            chunk = chunkFor(spec, chunkRows);
            rowsReturned += chunkRows;
        }
        openChunk = chunk;
        return chunk;
    }

    /// The `rows` of `full` over the output columns, or `full` itself when that is every row and
    /// every column.
    private Chunk selectedChunk(Chunk full, long[] rows) {
        List<ColumnName> names = outputNames;
        Map<ColumnName, Integer> positions = outputPositions;
        if (singleColumnIsSyntheticWrapper) {
            // the synthetic column was unpacked into the file's real columns
            names = List.copyOf(full.columns().keySet());
            positions = Chunk.positions(names);
        }
        if (rows.length == full.rowCount() && names.size() == full.columns().size()) {
            return full;
        }
        return full.select(rows, names, positions, this::onChunkClosed);
    }

    /// The first `chunkRows` rows of `spec`, in a chunk owning a new arena.
    private Chunk chunkFor(ChunkSpec spec, long chunkRows) {
        Arena arena = Arena.ofConfined();
        if (singleColumnIsSyntheticWrapper) {
            try {
                SequencedMap<ColumnName, Chunk.Column> columns = buildColumnMap(spec, arena);
                if (chunkRows < spec.rowCount()) {
                    columns = limitedColumns(columns, chunkRows);
                }
                return new Chunk(chunkRows, columns, arena, file.registry(), this::onChunkClosed);
            } catch (RuntimeException e) {
                arena.close();
                throw e;
            }
        }
        // Each column is decoded when first read, so a caller reading a few of the projected
        // columns (a sort key, a filter column) skips decoding the rest of the chunk. The decode
        // runs while the chunk is open, before next() can evict the flats it slices.
        return new Chunk(chunkRows, projectedNames, projectedPositions,
                i -> decodeColumn(spec, i, chunkRows, arena), arena, file.registry(), this::onChunkClosed);
    }

    private Chunk.Column decodeColumn(ChunkSpec spec, int i, long chunkRows, Arena arena) {
        Array array = decodeOrSlice(i, spec.columnLayouts()[i], spec.sliceOffsets()[i], spec.rowCount(), arena);
        if (chunkRows < spec.rowCount()) {
            array = Array.limited(array, chunkRows);
        }
        return new Chunk.Column(array, projectedDtypes.get(i));
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
            return new Chunk(spec.rowCount(), columns, arena, file.registry(), _ -> { });
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
    /// list falls back to each chunk's embedded `ArrayStats` (whatever the flat writer retained;
    /// `sum` is `null`, since it does not retain it). Either way a column that is absent
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
    /// @param name the column name
    /// @return per-zone stats in zone order; empty list if the file has no chunks
    public List<ArrayStats> columnZoneStats(ColumnName name) {
        if (chunks == null) {
            initialize();
        }
        List<ArrayStats> fromTable = decodeZoneTable(name, false);
        if (fromTable != null) {
            return fromTable;
        }
        // No zone-map table — surface each chunk's embedded ArrayStats (sum absent).
        return chunkStats(name);
    }

    /// Each scan window's exact embedded array stats for `column`, read from the covering chunk's
    /// data segment rather than the zone map: what [VortexReader#columnStats()] falls back on when
    /// the zone map only bounds a string column's min/max (Rust's `bounded_max`/`bounded_min`).
    /// A dictionary chunk reports its values pool's stats, which bound exactly the values it holds.
    ///
    /// @param name the column name
    /// @return per-window stats, positionally aligned with [#chunkRowCounts()]
    List<ArrayStats> chunkStats(ColumnName name) {
        if (chunks == null) {
            initialize();
        }
        List<ArrayStats> out = new ArrayList<>(chunks.size());
        for (ChunkSpec spec : chunks) {
            Layout layout = spec.layoutFor(name);
            if (layout != null && layout.isDict() && !layout.children().isEmpty()) {
                // child[0] = values, child[1] = codes — the order DictLayoutDecoder decodes.
                layout = layout.children().getFirst();
            }
            out.add(layout == null || !layout.isFlat() ? ArrayStats.empty() : readFlatStats(layout));
        }
        return out;
    }

    /// The column's zone-map table, one [Zone] per row, each carrying the range of column rows it
    /// describes. Unlike [#columnZoneStats(ColumnName)] there is no fallback to per-chunk stats: an
    /// empty list means the column has no zone map whose zones can be placed on its rows.
    ///
    /// @param name the column name
    /// @return the column's zones in row order; empty if it has no usable zone map
    public List<Zone> columnZones(ColumnName name) {
        List<Zone> zones = zonesFor(name, false);
        return zones == null ? List.of() : zones;
    }

    /// Decodes the column's zone-map table into one [ArrayStats] per zone, or returns `null` when
    /// the column has no zone map (so the caller falls back to per-chunk node stats). The table is
    /// a single flat segment encoding a struct with a subset of the `min`/`max`/`sum`/`null_count`
    /// fields; the schema is reconstructed from the layout metadata (legacy `vortex.stats` bitset
    /// or newer `vortex.zoned` aggregate specs — see [ZonedStatsSchema]). It is decoded into a
    /// short-lived confined arena and the scalar values are boxed out before the arena closes.
    ///
    /// Rust records string and binary zones as `bounded_max`/`bounded_min`: a truncated upper or
    /// lower bound, not the exact value. With `includeBounds` those bounds fill min/max, which is
    /// sound for pruning (a bound never excludes a matching row) but not an exact statistic, so the
    /// public surfaces that callers fold aggregates from leave min/max absent instead.
    private List<ArrayStats> decodeZoneTable(ColumnName column, boolean includeBounds) {
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
        DType.Struct statsDtype = ZonedStatsSchema.tableDtype(zoned, columnDtype);
        if (statsDtype == null || statsDtype.fieldNames().isEmpty()) {
            return null;
        }
        long nZones = statsFlat.rowCount();
        // statsFlat.rowCount() is an unvalidated field straight from the layout FlatBuffer
        // (PostscriptParser never bounds it — zone count is deliberately decoupled from the
        // data layout's chunk count, see this method's Javadoc). Below it sizes an ArrayList
        // and drives a per-zone loop via an `(int) nZones` cast: a negative value throws a raw
        // IllegalArgumentException from the ArrayList constructor instead of degrading to "no
        // zone map" like every other unusable shape this method already falls back on.
        if (nZones < 0 || nZones > Integer.MAX_VALUE) {
            return null;
        }
        SegmentSpec spec = file.footer().segmentSpecs().get(segIdx);
        try (Arena tableArena = Arena.ofConfined()) {
            Array decoded = file.decodeSegment(spec, statsDtype, nZones, tableArena);
            if (!(decoded instanceof StructArray table)) {
                return null;
            }
            Array minA = fieldOrNull(table, ZonedStatsSchema.Stat.MIN.fieldName());
            Array maxA = fieldOrNull(table, ZonedStatsSchema.Stat.MAX.fieldName());
            Array boundedMinA = includeBounds ? fieldOrNull(table, ZonedStatsSchema.BOUNDED_MIN) : null;
            Array boundedMaxA = includeBounds ? fieldOrNull(table, ZonedStatsSchema.BOUNDED_MAX) : null;
            Array sumA = fieldOrNull(table, ZonedStatsSchema.Stat.SUM.fieldName());
            Array nullCountA = fieldOrNull(table, ZonedStatsSchema.Stat.NULL_COUNT.fieldName());
            Array nanCountA = fieldOrNull(table, ZonedStatsSchema.Stat.NAN_COUNT.fieldName());
            // A legacy vortex.stats MIN/MAX is truncated (inexact) where its flag says so; a
            // vortex.zoned MIN/MAX is exact, and a bounded_min/bounded_max only ever a bound.
            Array minTruncatedA = fieldOrNull(table, ZonedStatsSchema.Stat.MIN_IS_TRUNCATED);
            Array maxTruncatedA = fieldOrNull(table, ZonedStatsSchema.Stat.MAX_IS_TRUNCATED);
            // Not pre-sized from nZones: it is bounded above only by Integer.MAX_VALUE (see the
            // guard above), and a single ArrayList allocation at that scale is itself an
            // OutOfMemoryError vector the security contract forbids. Growing incrementally keeps
            // memory proportional to what the loop below actually produces.
            List<ArrayStats> out = new ArrayList<>();
            for (long i = 0; i < nZones; i++) {
                Object nullCount = boxedScalar(nullCountA, i);
                Object nanCount = boxedScalar(nanCountA, i);
                Object min = minA != null ? boxedScalar(minA, i) : boxedScalar(boundedMinA, i);
                Object max = maxA != null ? boxedScalar(maxA, i) : boundedMaxScalar(boundedMaxA, i);
                out.add(new ArrayStats(
                        min,
                        min != null && minA != null && !Boolean.TRUE.equals(boxedScalar(minTruncatedA, i)),
                        max,
                        max != null && maxA != null && !Boolean.TRUE.equals(boxedScalar(maxTruncatedA, i)),
                        boxedScalar(sumA, i),
                        null, null, null,
                        nullCount == null ? null : ((Number) nullCount).longValue(),
                        null,
                        nanCount == null ? null : ((Number) nanCount).longValue()));
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
        if (idx < 0) {
            return null;
        }
        return firstZoned(root.children().get(fieldChildOffset(struct) + idx));
    }

    /// Index of field 0 among the root struct layout's children: a nullable struct's layout leads
    /// with its validity child (open checks the count, as Rust's `validate_children` does), so the
    /// columns start one later. The root validity itself is not applied: [Chunk] has no row-level
    /// nulls, so a null root row reads as its field values.
    private static int fieldChildOffset(DType.Struct root) {
        return root.nullable() ? 1 : 0;
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

    /// The bound of a `bounded_max` state at zone `i`, or `null` when the state is absent, the zone
    /// has no non-null value, or the bound is unknown (no bound fits the byte limit).
    private static Object boundedMaxScalar(Array state, long i) {
        if (state == null) {
            return null;
        }
        if (state instanceof MaskedArray masked) {
            if (!masked.isValid(i)) {
                return null;
            }
            return boundedMaxScalar(masked.inner(), i);
        }
        if (!(state instanceof StructArray struct)) {
            return null;
        }
        if (Boolean.TRUE.equals(boxedScalar(fieldOrNull(struct, ZonedStatsSchema.UNKNOWN), i))) {
            return null;
        }
        return boxedScalar(fieldOrNull(struct, ZonedStatsSchema.BOUND), i);
    }

    private static Array fieldOrNull(StructArray table, ColumnName name) {
        if (((DType.Struct) table.dtype()).fieldNames().contains(name)) {
            return table.field(name);
        }
        return null;
    }

    /// Reads the boxed scalar at index `i` from a (possibly nullable) stats column, or `null`
    /// when the array is absent or the position is invalid.
    ///
    /// A `U8`/`U16`/`U32` array zero-extends into a non-negative [Long], mirroring
    /// [io.github.dfa1.vortex.reader.compute.Values#valueAt(Array, long)]'s widening for row data —
    /// the narrow signed accessors (`getInt`/`getShort`/`getByte`) sign-extend, so an unsigned value
    /// with its high bit set would otherwise box as a negative number a plain (or even
    /// unsigned-aware) `long` compare against a zero-extended filter literal gets backwards. `U64`
    /// is already at full width (no wider box to zero-extend into) and boxes unchanged.
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
        boolean unsigned = array.dtype().isUnsigned();
        return switch (array) {
            case LongArray a -> a.getLong(i);
            case IntArray a -> unsigned ? Integer.toUnsignedLong(a.getInt(i)) : (Object) a.getInt(i);
            case DoubleArray a -> a.getDouble(i);
            case FloatArray a -> a.getFloat(i);
            case Float16Array a -> a.getFloat(i);
            case ShortArray a -> unsigned ? a.getShort(i) & 0xFFFFL : (Object) a.getShort(i);
            case ByteArray a -> unsigned ? a.getByte(i) & 0xFFL : (Object) a.getByte(i);
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
        if (pending != null) {
            pending.close();
            pending = null;
        }
        if (sharedFlats != null) {
            for (CachedFlat cached : sharedFlats.values()) {
                cached.arena().close();
            }
            sharedFlats = null;
        }
        if (dictValuesByColumn != null) {
            dictValuesByColumn.values().forEach(cached -> cached.values().close());
            dictValuesByColumn = null;
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

        List<ColumnName> projection = options.columns();
        if (rootLayout.isStruct() && rootDtype instanceof DType.Struct structDtype) {
            // the filter's columns are decoded too, to test each row, even when not projected
            Set<ColumnName> filterColumns = new HashSet<>();
            if (filter != null) {
                collectColumns(filter, filterColumns);
            }
            int offset = fieldChildOffset(structDtype);
            for (int i = 0; i < structDtype.fieldNames().size(); i++) {
                ColumnName colName = structDtype.fieldNames().get(i);
                DType colDtype = structDtype.fieldTypes().get(i);
                if (!projection.isEmpty() && !projection.contains(colName) && !filterColumns.contains(colName)) {
                    continue;
                }
                Layout colTop = rootLayout.children().get(offset + i);
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
            singleColumnIsSyntheticWrapper = true;
        }

        projectedNames = List.copyOf(columnDtypes.keySet());
        projectedDtypes = List.copyOf(columnDtypes.values());
        projectedPositions = Chunk.positions(projectedNames);
        outputNames = projectedNames;
        if (!projection.isEmpty() && !singleColumnIsSyntheticWrapper) {
            outputNames = projectedNames.stream().filter(projection::contains).toList();
        }
        outputPositions = Chunk.positions(outputNames);
        lastCoveringFlats = new Layout[projectedNames.size()];
        chunks = buildChunks(columnFlats);
    }

    /// The columns of a file without per-column layouts: its one synthetic column, unpacked by
    /// field when it decodes to a struct.
    private SequencedMap<ColumnName, Chunk.Column> buildColumnMap(ChunkSpec chunk, Arena arena) {
        Array arr = decodeOrSlice(0, chunk.columnLayouts()[0], chunk.sliceOffsets()[0], chunk.rowCount(), arena);
        if (arr instanceof StructArray sa) {
            return expandStruct(sa);
        }
        return singleColumn(arr);
    }

    /// Builds the column map for [#decodeChunkAt(int)]. Identical decode to [#decodeColumn]
    /// except that a covering chunk spanning several windows is decoded into `arena` and sliced
    /// there, so the resulting [Chunk] owns every buffer and survives this iterator's close.
    private SequencedMap<ColumnName, Chunk.Column> buildSelfContainedColumnMap(ChunkSpec chunk, Arena arena) {
        Layout[] layouts = chunk.columnLayouts();
        long[] sliceOffsets = chunk.sliceOffsets();
        int n = projectedNames.size();
        if (n == 1) {
            Array arr = decodeOrSliceSelfContained(0, layouts[0], sliceOffsets[0], chunk.rowCount(), arena);
            if (singleColumnIsSyntheticWrapper && arr instanceof StructArray sa) {
                return expandStruct(sa);
            }
            return singleColumn(arr);
        }
        var scratch = LinkedHashMap.<ColumnName, Chunk.Column>newLinkedHashMap(n);
        for (int i = 0; i < n; i++) {
            scratch.put(projectedNames.get(i), new Chunk.Column(
                    decodeOrSliceSelfContained(i, layouts[i], sliceOffsets[i], chunk.rowCount(), arena),
                    projectedDtypes.get(i)));
        }
        return unmodifiable(scratch);
    }

    private SequencedMap<ColumnName, Chunk.Column> singleColumn(Array array) {
        var map = LinkedHashMap.<ColumnName, Chunk.Column>newLinkedHashMap(1);
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
        // Canonicalized once, as Rust executes a chunk before slicing it per split: each window's
        // slice then reads a flat buffer instead of re-resolving lazy composite children per row.
        return sharedFlats.computeIfAbsent(flat, f -> {
            Arena flatArena = Arena.ofConfined();
            return new CachedFlat(flatArena, canonicalShared(decodeLayout(f, dtype, flatArena), flatArena));
        }).array();
    }

    /// Canonicalizes a shared flat, except that an `i32` dict stays a dict over flat codes and a
    /// flat pool: each window slices just its codes, and a fold gathers off the pool, where
    /// expanding it first wrote and re-read a 4-byte value for every row of the flat.
    private static Array canonicalShared(Array array, Arena arena) {
        return switch (array) {
            case MaskedArray m -> new MaskedArray(canonicalShared(m.inner(), arena), m.validity());
            case DictIntArray d -> DictIntArray.of(d.dtype(), d.length(),
                    (IntArray) CanonicalArrays.of(d.values(), arena), CanonicalArrays.of(d.codes(), arena));
            default -> CanonicalArrays.of(array, arena);
        };
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

    static Array sliceArray(Array full, long offset, long length, DType dtype) {
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
            // A dict window shares the pool and slices only its codes.
            case DictIntArray d -> DictIntArray.of(dtype, length, d.values(),
                    sliceArray(d.codes(), offset, length, d.codes().dtype()));
            case IntArray a -> new OffsetIntArray(dtype, length, a, offset);
            case DoubleArray a -> new OffsetDoubleArray(dtype, length, a, offset);
            case FloatArray a -> new OffsetFloatArray(dtype, length, a, offset);
            case ShortArray a -> new OffsetShortArray(dtype, length, a, offset);
            case ByteArray a -> new OffsetByteArray(dtype, length, a, offset);
            case BoolArray a -> new OffsetBoolArray(dtype, length, a, offset);
            case NullArray _ -> new NullArray(dtype, length);
            case VarBinArray a -> new VarBinSlicedArray(dtype, length, a, offset);
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
            case ListArray a -> {
                // Offsets store absolute positions into the shared elements array, so only the
                // offsets sub-range needs slicing (length+1 entries for the window's row
                // boundaries); elements stay shared unchanged, same as ListArray#limited.
                DType.List ld = (DType.List) dtype;
                Array offsetsSlice = sliceArray(a.offsets(), offset, length + 1, a.offsets().dtype());
                yield new ListArray(ld, length, a.elements(), offsetsSlice);
            }
            case FixedSizeListArray a -> {
                // Unlike ListArray, element position is computed from the local row index
                // (row * fixedSize), so the elements sub-range itself must be sliced to the
                // window's absolute position rather than shared unchanged.
                DType.FixedSizeList fd = (DType.FixedSizeList) dtype;
                int fixedSize = a.fixedSize();
                Array elementsSlice = sliceArray(a.elements(), offset * fixedSize, length *fixedSize, fd.elementType());
                yield new FixedSizeListArray(fd, length, elementsSlice);
            }
            // Decimals: rebuild each representation over the window - a byte-parts array slices its
            // mantissa child, a buffer-backed one its buffer (zero-copy), a constant just its length.
            case LazyDecimalBytePartsArray a -> new LazyDecimalBytePartsArray(dtype, length,
                    sliceArray(a.msp(), offset, length, a.msp().dtype()));
            case LazyDecimalArray a -> new LazyDecimalArray(dtype, length,
                    a.buf().asSlice(offset * a.byteWidth(), length * a.byteWidth()), a.byteWidth());
            case LazyConstantDecimalArray a -> new LazyConstantDecimalArray(dtype, length, a.value(), a.byteWidth());
            case ListViewArray a -> {
                // Like ListArray: offsets and sizes address the shared elements absolutely, so only
                // the per-row offsets and sizes are sliced.
                yield new ListViewArray((DType.List) dtype, length, a.elements(),
                        sliceArray(a.offsets(), offset, length, a.offsets().dtype()),
                        sliceArray(a.sizes(), offset, length, a.sizes().dtype()));
            }
            case MapArray a -> new MapArray((DType.Map) dtype, length,
                    sliceArray(a.entries(), offset, length, a.entries().dtype()));
            // A sparse union's children are all row-aligned with it: slice each to the window.
            case UnionArray a -> {
                DType.Union ud = (DType.Union) dtype;
                var slicedVariants = new ArrayList<Array>(a.variantCount());
                for (int i = 0; i < a.variantCount(); i++) {
                    slicedVariants.add(sliceArray(a.variant(i), offset, length, ud.variantTypes().get(i)));
                }
                yield new UnionArray(ud, length, sliceArray(a.typeIds(), offset, length, a.typeIds().dtype()), slicedVariants);
            }
            // Float16, Generic, Variant and Unknown arrays have no slice yet; they fail loudly here.
            default -> throw new VortexException(
                    "scan: cannot slice shared array of type " + full.getClass().getSimpleName());
        };
    }

    private Array decodeLayout(Layout layout, DType dtype, SegmentAllocator arena) {
        return file.layoutRegistry().decode(new ScanLayoutContext(file, arena), layout, dtype);
    }

    // ── Limit truncation ─────────────────────────────────────────────────────

    private boolean canPruneChunk(ChunkSpec chunk, ScanFilter filter) {
        return switch (filter) {
            case ScanFilter.And(var filters) -> {
                // Conjunction: pruning any one conjunct prunes the whole chunk (short-circuit).
                for (ScanFilter f : filters) {
                    if (canPruneChunk(chunk, f)) {
                        yield true;
                    }
                }
                yield false;
            }
            case ScanFilter.Column(var col, var predicate) -> {
                // A valid-but-absent name yields no layout (no pruning), matching the row-level
                // scan it gates.
                Layout flat = chunk.layoutFor(col);
                if (flat == null) {
                    yield false;
                }
                // Each zone is judged on its own rows, not the window's: a zone can be wider than
                // the window (Rust's vortex.zoned) or narrower (a dict column's code chunk, or 8192-row
                // zones in a larger chunk), and the IS NOT NULL test compares nullCount against it.
                List<Zone> zones = zonesOverlapping(chunk, col);
                if (zones == null) {
                    yield canPrune(predicate, readFlatStats(flat), flat.rowCount(), columnDType(col))
                            || dictMatchesNothing(flat, col, predicate);
                }
                // The window has no matching row iff none of the zones covering it has one.
                for (Zone zone : zones) {
                    if (!canPrune(predicate, zone.stats(), zone.rowCount(), columnDType(col))) {
                        yield dictMatchesNothing(flat, col, predicate);
                    }
                }
                yield true;
            }
        };
    }

    /// Whether no row of the dictionary `layout` can satisfy `predicate` because none of its values
    /// does, decided from the values alone, once per dictionary and predicate: no code is read, so a
    /// literal missing from a shared dictionary skips every chunk. Rust evaluates a dict filter over
    /// the values too, but always reads the codes (see `docs/compatibility.md`).
    ///
    /// @return `false` when `layout` is not a dictionary, when a null row could satisfy `predicate`,
    ///         or when the dictionary holds more values than rows
    private boolean dictMatchesNothing(Layout layout, ColumnName col, Predicate predicate) {
        if (!layout.isDict() || layout.children().size() < 2 || !rejectsNull(predicate)) {
            return false;
        }
        if (dictPruneCache == null) {
            dictPruneCache = new IdentityHashMap<>();
        }
        return dictPruneCache.computeIfAbsent(layout, l -> new IdentityHashMap<>())
                .computeIfAbsent(predicate, p -> noValueMatches(layout, col, p));
    }

    private boolean noValueMatches(Layout dict, ColumnName col, Predicate predicate) {
        Chunk pool = dictValues(dict, col);
        // Every value a code can reach is tested: the decoded pool's length, not the layout's claim,
        // which a malformed file can set shorter or longer. Testing more values than there are rows
        // could cost more than the scan it saves, as the row-level dictionary leaf declines too.
        long size = pool.rowCount();
        if (size > dict.rowCount()) {
            return false;
        }
        LongPredicate matches = Compute.matcher(pool, new ScanFilter.Column(col, predicate));
        for (long v = 0; v < size; v++) {
            if (matches.test(v)) {
                return false;
            }
        }
        return true;
    }

    /// The values of dictionary `dict` as a one-column chunk, decoded once for every predicate on
    /// `col` tested against them. Only the latest dictionary of each column is kept: the scan never
    /// returns to an earlier one, and a chunk-per-dictionary column would otherwise pile up pools.
    private Chunk dictValues(Layout dict, ColumnName col) {
        if (dictValuesByColumn == null) {
            dictValuesByColumn = new HashMap<>();
        }
        DictValues cached = dictValuesByColumn.get(col);
        if (cached != null && cached.dict() == dict) {
            return cached.values();
        }
        if (cached != null) {
            cached.values().close();
        }
        DType dtype = columnDType(col);
        Arena arena = Arena.ofConfined();
        Chunk pool;
        try {
            Array decoded = decodeLayout(dict.children().getFirst(), dtype, arena);
            var columns = new LinkedHashMap<ColumnName, Chunk.Column>();
            columns.put(col, new Chunk.Column(decoded, dtype));
            pool = new Chunk(decoded.length(), columns, arena, file.registry(), c -> { });
        } catch (RuntimeException e) {
            dictValuesByColumn.remove(col);
            arena.close();
            throw e;
        }
        dictValuesByColumn.put(col, new DictValues(dict, pool));
        return pool;
    }

    /// A dictionary layout's decoded values, kept for the pruning checks of one column.
    ///
    /// @param dict   the dictionary layout
    /// @param values its values as a one-column chunk owning their arena
    private record DictValues(Layout dict, Chunk values) {
    }

    /// Whether `predicate` is false on a null value, as a row with a null dictionary code has.
    private static boolean rejectsNull(Predicate predicate) {
        return switch (predicate) {
            case Predicate.IsNull _ -> false;
            case Predicate.And(var left, var right) -> rejectsNull(left) || rejectsNull(right);
            case Predicate.Or(var left, var right) -> rejectsNull(left) && rejectsNull(right);
            default -> true;
        };
    }

    /// Returns the zones of `col` that together cover `chunk`'s window, in row order, or `null` when
    /// the column has no usable zone map or its zones do not reach the window's end — in which case
    /// the caller falls back to the chunk's own embedded stats. Reading here decodes (and caches) one
    /// small zone-map segment per column for the whole scan, instead of [#readFlatStats(Layout)]'s
    /// per-chunk read of the chunk's full data segment — the difference that makes pruning over
    /// HTTP actually cheaper than not pruning.
    private List<Zone> zonesOverlapping(ChunkSpec chunk, ColumnName col) {
        List<Zone> zones = zonesFor(col, true);
        if (zones == null) {
            return null;
        }
        long start = chunk.windowStart();
        long end = start + chunk.rowCount();
        // Last zone starting at or before the window (zones are sorted and contiguous from row 0).
        int lo = 0;
        int hi = zones.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (zones.get(mid).firstRow() <= start) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        int last = lo;
        while (last < zones.size() && zones.get(last).firstRow() + zones.get(last).rowCount() < end) {
            last++;
        }
        if (last == zones.size()) {
            return null;
        }
        return zones.subList(lo, last + 1);
    }

    /// Row ranges of the zones in `zoned`'s table as cumulative starts (the final entry is the end
    /// of the last zone), or `null` when the layout does not describe exactly `zoneCount` non-empty
    /// zones. The three `vortex.zoned`-family shapes place their zones differently:
    ///
    /// - Rust's `vortex.zoned` declares a uniform **zone length** independent of chunk boundaries,
    ///   dictionary columns included;
    /// - this writer's legacy `vortex.stats` dictionary column has one zone per **code chunk**
    ///   ([#dictCodeChunkStarts]) — the dict node above them is decoded as a unit, so chunk planning
    ///   sees one full-range chunk;
    /// - this writer's legacy `vortex.stats` emits one zone per **physical chunk**, in chunk order
    ///   (`VortexWriter#flushZoneMaps`). Its declared zone length only caps a chunk's size (a batch
    ///   smaller than the cap becomes its own, smaller chunk), so it cannot drive arithmetic.
    private static long[] zoneStarts(Layout zoned, int zoneCount) {
        long[] starts;
        if (zoned.layoutId() == LayoutId.ZONED) {
            // Always a stride, whatever the data child: Rust's zones never follow dict code chunks.
            starts = strideStarts(ZonedStatsSchema.aggregateZoneLength(zoned.metadata()), zoned.rowCount(), zoneCount);
        } else {
            starts = dictCodeChunkStarts(zoned);
        }
        if (starts == null && zoned.layoutId() != LayoutId.ZONED && !zoned.children().isEmpty()) {
            List<Layout> flats = new ArrayList<>();
            collectFlats(zoned.children().getFirst(), flats);
            starts = cumulativeStarts(flats);
        }
        // Validated because every input is an unchecked layout field: a table whose zone count
        // disagrees with the layout, or a zero-length zone (whose nullCount == rowCount test would
        // prune IS NOT NULL on 0 == 0), makes the whole zone map unusable rather than misattributed.
        if (starts == null || starts.length != zoneCount + 1) {
            return null;
        }
        for (int i = 0; i < zoneCount; i++) {
            if (starts[i] >= starts[i + 1]) {
                return null;
            }
        }
        return starts;
    }

    /// Starts of `zoneCount` zones of `zoneLen` rows over `rows` rows, the last one clamped to
    /// `rows`; `null` when that many zones cannot cover exactly `rows` rows.
    private static long[] strideStarts(long zoneLen, long rows, int zoneCount) {
        if (zoneLen <= 0 || rows <= 0 || zoneCount != Math.ceilDiv(rows, zoneLen)) {
            return null;
        }
        long[] starts = new long[zoneCount + 1];
        for (int i = 0; i < zoneCount; i++) {
            starts[i] = i * zoneLen;
        }
        starts[zoneCount] = rows;
        return starts;
    }

    private static long[] cumulativeStarts(List<Layout> chunks) {
        long[] starts = new long[chunks.size() + 1];
        for (int i = 0; i < chunks.size(); i++) {
            starts[i + 1] = starts[i] + chunks.get(i).rowCount();
        }
        return starts;
    }

    /// Cumulative row starts of a dictionary column's per-chunk code arrays (the final entry is the
    /// column's total row count), or `null` when `zoned` does not wrap a dict whose codes are
    /// chunked — the only shape this applies to.
    private static long[] dictCodeChunkStarts(Layout zoned) {
        if (zoned.children().isEmpty()) {
            return null;
        }
        Layout data = zoned.children().getFirst();
        // child[0] = values, child[1] = codes — the order DictLayoutDecoder decodes.
        if (!data.isDict() || data.children().size() < 2) {
            return null;
        }
        Layout codes = data.children().get(1);
        if (!codes.isChunked()) {
            return null;
        }
        return cumulativeStarts(codes.children().subList(chunkedDataStart(codes), codes.children().size()));
    }

    /// The column's zones, row ranges attached, or `null` when it has no usable zone map.
    ///
    /// @param includeBounds `true` for pruning, where Rust's bounded string min/max may stand in
    ///                      for the exact values; see [#decodeZoneTable(ColumnName, boolean)]
    private List<Zone> zonesFor(ColumnName col, boolean includeBounds) {
        Map<ColumnName, List<Zone>> cache;
        if (includeBounds) {
            if (pruningZoneCache == null) {
                pruningZoneCache = new HashMap<>();
            }
            cache = pruningZoneCache;
        } else {
            if (zoneCache == null) {
                zoneCache = new HashMap<>();
            }
            cache = zoneCache;
        }
        List<Zone> cached = cache.get(col);
        if (cached != null) {
            return cached;
        }
        List<ArrayStats> stats = decodeZoneTable(col, includeBounds);
        if (stats == null || stats.isEmpty()) {
            return null;
        }
        long[] starts = zoneStarts(findZonedLayout(file.layout(), col), stats.size());
        if (starts == null) {
            return null;
        }
        List<Zone> zones = new ArrayList<>(stats.size());
        for (int i = 0; i < stats.size(); i++) {
            zones.add(new Zone(starts[i], starts[i + 1] - starts[i], stats.get(i)));
        }
        List<Zone> result = List.copyOf(zones);
        cache.put(col, result);
        return result;
    }

    /// Tests whether `predicate`, compiled against a chunk's zone-map statistics, can prove that no
    /// row in the chunk can match — in which case the chunk is skipped. Pruning is strictly
    /// conservative: every branch returns `false` (do not prune) when a needed statistic is missing
    /// or the outcome is uncertain, so a chunk is skipped only when it provably holds no match. All
    /// ordering routes through [Compare#values(Object, Object, DType)] so the test stays
    /// width-agnostic and unsigned/float-aware, identically to the row-level scan it gates. Floats order
    /// totally, as in Rust: `-0.0 < 0.0`, `NaN` is the maximum.
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
        if (ct instanceof DType.Primitive p && p.ptype().isFloating()
                && (stats.nanCount() == null || stats.nanCount() != 0)) {
            // Float min/max skip NaN, but filters order floats totally (Rust), where NaN is the
            // maximum: a chunk that may hold one (nan_count absent or nonzero) has a NaN upper bound.
            // nan_count is u64: a count past 2^63 reads as a negative Long, still not zero.
            max = Double.NaN;
        }
        return switch (predicate) {
            // val < min || val > max → no row equals val.
            case Predicate.Eq(var v) -> min != null && max != null
                    && (Compare.values(v, min, ct) < 0 || Compare.values(v, max, ct) > 0);
            // Every row equals val (min == max == val) and no nulls → no row differs. A null row is
            // neither = nor != val under three-valued logic, so require a provably null-free chunk.
            // Inexact bounds only bracket the values, so min == max proves nothing without both exact.
            case Predicate.Neq(var v) -> min != null && max != null && stats.minExact() && stats.maxExact()
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
        // degrades to "no stats" (empty) and never aborts the scan.
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
        try {
            var fbArray = io.github.dfa1.vortex.core.fbs.FbsArray.getRootAsFbsArray(IoBounds.slice(seg, fbStart, fbLen));
            io.github.dfa1.vortex.core.fbs.FbsArrayNode root = fbArray.root();
            if (root == null) {
                return ArrayStats.empty();
            }
            return ArrayStats.fromFbs(root.stats());
        } catch (VortexException _) {
            // The FlatBuffer verifier rejected the stats segment: malformed stats read as none.
            return ArrayStats.empty();
        }
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
            long windowStart, long rowCount, ColumnName[] columnNames, Layout[] columnLayouts,
            long[] sliceOffsets) {
        Layout layoutFor(ColumnName col) {
            int i = indexFor(col);
            return i < 0 ? null : columnLayouts[i];
        }

        private int indexFor(ColumnName col) {
            for (int i = 0; i < columnNames.length; i++) {
                if (columnNames[i].equals(col)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
