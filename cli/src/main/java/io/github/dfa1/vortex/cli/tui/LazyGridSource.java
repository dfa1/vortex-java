package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.array.Array;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/// Streaming row source for the grid viewer.
///
/// At any moment exactly one [Chunk] is held open. When the viewport scrolls
/// into a different chunk the held chunk is closed and the next one is decoded;
/// scrolling backwards reopens the scan from the start of the file. Only the
/// rows the viewport actually paints are formatted into strings — so cursor
/// moves inside the live chunk allocate nothing beyond the visible window
/// (typically 30 rows), regardless of how many rows the chunk contains.
///
/// All [VortexHandle] I/O is routed through the supplied [IoWorker] so the
/// reader's confined `Arena` is never crossed from the render thread.
public final class LazyGridSource implements AutoCloseable {

    private static final int COLUMN_MARGIN = 2;

    private final VortexHandle handle;
    private final IoWorker worker;
    private final List<String> columns;
    private final List<DType> columnDtypes;
    private final long totalRows;
    private final int chunkCount;
    private final long[] chunkStartRows;

    private ScanIterator iter;
    private int iterNextChunk;
    private Chunk currentChunk;
    private volatile int currentChunkIdx = -1;
    private Array[] currentColumns;
    private volatile boolean closed;
    private volatile Request latest;
    private volatile Loaded loaded;
    private volatile Failure failure;

    /// A window the render thread asked [#peekRows(long, int, int, int)] for.
    private record Request(long start, int count, int colFrom, int colTo) {
    }

    /// Why the load of `request` failed.
    private record Failure(Request request, RuntimeException cause) {
    }

    /// The window the worker last finished formatting; rows are immutable once published and
    /// hold text only in columns `[colFrom, colTo)`.
    @SuppressWarnings("java:S6218") // internal data carrier; the rows are never compared.
    private record Loaded(long start, int colFrom, int colTo, String[][] rows) {
    }

    /// Result of [#peekRows(long, int, int, int)].
    ///
    /// @param rows     one slot per requested row; rows not loaded yet are filled with `…`
    /// @param complete true when no row is a placeholder, so nothing is left to wait for
    @SuppressWarnings("java:S6218") // internal data carrier; the rows are never compared.
    public record Rows(String[][] rows, boolean complete) {
    }

    /// Builds a lazy source over `handle`, walking the layout once up front to
    /// derive chunk boundaries.
    ///
    /// @param handle open Vortex file handle owned by `worker`
    /// @param worker I/O dispatcher for the handle's confined thread
    /// @return initialized source
    /// @throws InterruptedException if the calling thread is interrupted while
    ///                              waiting for the worker
    public static LazyGridSource open(VortexHandle handle, IoWorker worker)
            throws InterruptedException {
        return new LazyGridSource(handle, worker);
    }

    private LazyGridSource(VortexHandle handle, IoWorker worker) throws InterruptedException {
        this.handle = handle;
        this.worker = worker;
        if (!(handle.dtype() instanceof DType.Struct schema)) {
            throw new VortexException("view requires struct root dtype, got " + handle.dtype());
        }
        this.columns = schema.fieldNames().stream().map(ColumnName::value).toList();
        this.columnDtypes = schema.fieldTypes();

        AtomicReference<long[]> rowCountsRef = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        worker.runAndAwait(() -> {
            try {
                ScanIterator probe = handle.scan(ScanOptions.all());
                rowCountsRef.set(probe.chunkRowCounts());
                this.iter = probe;
                this.iterNextChunk = 0;
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        long[] chunkRowCounts = rowCountsRef.get();
        this.chunkCount = chunkRowCounts.length;
        this.chunkStartRows = new long[chunkCount + 1];
        long running = 0;
        for (int i = 0; i < chunkCount; i++) {
            chunkStartRows[i] = running;
            running += chunkRowCounts[i];
        }
        chunkStartRows[chunkCount] = running;
        this.totalRows = running;
    }

    /// Column names in display order.
    ///
    /// @return immutable list of column names
    public List<String> columns() {
        return columns;
    }

    /// Total logical row count across all chunks.
    ///
    /// @return row count
    public long totalRows() {
        return totalRows;
    }

    /// Total chunk count in the file.
    ///
    /// @return chunk count
    public int chunkCount() {
        return chunkCount;
    }

    /// Index of the chunk currently held open, or `-1` when no chunk is loaded.
    ///
    /// @return current chunk index
    public int currentChunkIndex() {
        return currentChunkIdx;
    }

    /// Returns formatted rows for the absolute-row window `[startAbsRow, startAbsRow + count)`.
    ///
    /// The window may straddle a chunk boundary; this method handles the chunk
    /// transitions transparently. Returned rows are independent `String[]`
    /// arrays — safe to keep after the underlying chunk is closed.
    ///
    /// @param startAbsRow first absolute row in the window
    /// @param count       number of rows to read; rows past `totalRows()` are
    ///                    returned as empty `String[]`
    /// @return array of length `count`, one row per slot
    /// @throws InterruptedException if the calling thread is interrupted while
    ///                              waiting for the worker
    /// @throws VortexException      if this source has already been closed
    public String[][] readRows(long startAbsRow, int count) throws InterruptedException {
        if (closed) {
            throw new VortexException("LazyGridSource closed");
        }
        String[][] out = new String[count][];
        if (count == 0 || startAbsRow >= totalRows) {
            for (int i = 0; i < count; i++) {
                out[i] = emptyRow();
            }
            return out;
        }
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        worker.runAndAwait(() -> {
            try {
                fillWindow(out, startAbsRow, count, 0, columns.size());
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        for (int i = 0; i < count; i++) {
            if (out[i] == null) {
                out[i] = emptyRow();
            }
        }
        return out;
    }

    /// Non-blocking variant of [#readRows(long, int)] for the render loop: returns what is
    /// already formatted and asks the worker for the rest.
    ///
    /// Rows the worker has not delivered yet come back as `…` cells with
    /// [Rows#complete()] false; the caller redraws later. A request superseded by a newer
    /// one before the worker reaches it is dropped. Call from one thread only.
    ///
    /// Only columns `[colFrom, colTo)` are decoded, widened by a small margin so a one-column
    /// scroll finds its data already loaded; a cell outside that range is `null`.
    ///
    /// @param startAbsRow first absolute row in the window
    /// @param count       number of rows to read
    /// @param colFrom     first visible column
    /// @param colTo       one past the last visible column
    /// @return the cached rows plus whether any visible cell is still loading
    /// @throws VortexException if this source is closed or a background load failed
    public Rows peekRows(long startAbsRow, int count, int colFrom, int colTo) {
        if (closed) {
            throw new VortexException("LazyGridSource closed");
        }
        Request wanted = new Request(startAbsRow, count,
                Math.max(0, colFrom - COLUMN_MARGIN), Math.min(columns.size(), colTo + COLUMN_MARGIN));
        Failure failed = failure;
        if (failed != null && failed.request.equals(wanted)) {
            throw failed.cause;
        }
        Loaded snapshot = loaded;
        boolean columnsLoaded = snapshot != null && colFrom >= snapshot.colFrom && colTo <= snapshot.colTo;
        String[][] out = new String[count][];
        boolean complete = true;
        for (int i = 0; i < count; i++) {
            long row = startAbsRow + i;
            if (row >= totalRows) {
                out[i] = emptyRow();
            } else if (columnsLoaded && row >= snapshot.start && row - snapshot.start < snapshot.rows.length) {
                out[i] = snapshot.rows[(int) (row - snapshot.start)];
            } else {
                out[i] = loadingRow();
                complete = false;
            }
        }
        if (!complete) {
            request(wanted);
        }
        return new Rows(out, complete);
    }

    private void request(Request request) {
        if (request.equals(latest)) {
            return;
        }
        latest = request;
        failure = null;
        worker.submit(() -> {
            // A load superseded by a newer window, or queued before close, is skipped: each one
            // costs round trips over HTTP.
            if (closed || latest != request) {
                return;
            }
            String[][] rows = new String[request.count][];
            try {
                fillWindow(rows, request.start, request.count, request.colFrom, request.colTo);
                loaded = new Loaded(request.start, request.colFrom, request.colTo, rows);
            } catch (RuntimeException e) {
                // Tied to its window, so one failed load (a transient 503) does not poison the rest.
                if (latest == request) {
                    failure = new Failure(request, e);
                }
            }
        });
    }

    private String[] loadingRow() {
        String[] row = new String[columns.size()];
        Arrays.fill(row, "…");
        return row;
    }

    private void fillWindow(String[][] out, long startAbsRow, int count, int colFrom, int colTo) {
        long row = startAbsRow;
        int slot = 0;
        while (slot < count) {
            if (row >= totalRows) {
                out[slot++] = emptyRow();
                continue;
            }
            int chunkIdx = findChunk(row);
            loadChunk(chunkIdx);
            prefetchColumns(colFrom, colTo);
            long chunkStart = chunkStartRows[chunkIdx];
            long chunkEnd = chunkStartRows[chunkIdx + 1];
            long limit = Math.min(chunkEnd, startAbsRow + count);
            while (row < limit && slot < count) {
                out[slot++] = formatRow(row - chunkStart, colFrom, colTo);
                row++;
            }
        }
    }

    /// Returns a single formatted row at absolute index `absRow`.
    ///
    /// @param absRow row index in `[0, totalRows())`
    /// @return one cell per column
    /// @throws InterruptedException if the calling thread is interrupted while
    ///                              waiting for the worker
    /// @throws VortexException      if `absRow` is out of bounds or this source
    ///                              has already been closed
    public String[] row(long absRow) throws InterruptedException {
        if (absRow < 0 || absRow >= totalRows) {
            throw new VortexException("row index out of bounds: " + absRow);
        }
        return readRows(absRow, 1)[0];
    }

    private int findChunk(long absRow) {
        int lo = 0;
        int hi = chunkCount - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (chunkStartRows[mid] <= absRow) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /// Worker-thread-only. Ensures the chunk with index `chunkIdx` is the
    /// currently open chunk, advancing or reopening the scan as needed.
    private void loadChunk(int chunkIdx) {
        if (currentChunkIdx == chunkIdx && currentChunk != null) {
            return;
        }
        closeCurrentChunk();
        if (chunkIdx < iterNextChunk) {
            iter.close();
            iter = handle.scan(ScanOptions.all());
            iterNextChunk = 0;
        }
        while (iterNextChunk < chunkIdx && iter.hasNext()) {
            try (var _ = iter.next()) {
                // skip
            }
            iterNextChunk++;
        }
        if (!iter.hasNext()) {
            throw new VortexException("scan exhausted before reaching chunk " + chunkIdx);
        }
        Chunk chunk = iter.next();
        iterNextChunk++;
        currentChunk = chunk;
        currentChunkIdx = chunkIdx;
        currentColumns = new Array[columns.size()];
    }

    /// Worker-thread-only. Starts the range requests of the columns about to be formatted all
    /// at once, so over HTTP they cost one round trip together rather than one each.
    private void prefetchColumns(int colFrom, int colTo) {
        List<ColumnName> wanted = new ArrayList<>();
        for (int c = colFrom; c < colTo; c++) {
            if (currentColumns[c] == null) {
                wanted.add(ColumnName.of(columns.get(c)));
            }
        }
        currentChunk.prefetch(wanted);
    }

    private void closeCurrentChunk() {
        if (currentChunk != null) {
            currentChunk.close();
            currentChunk = null;
            currentChunkIdx = -1;
            currentColumns = null;
        }
    }

    /// Worker-thread-only. Formats columns `[colFrom, colTo)` of one row; the others stay
    /// `null`. A column is decoded (one range request over HTTP) the first time it is formatted.
    private String[] formatRow(long inChunk, int colFrom, int colTo) {
        String[] row = new String[columns.size()];
        for (int c = colFrom; c < colTo; c++) {
            if (currentColumns[c] == null) {
                currentColumns[c] = currentChunk.column(ColumnName.of(columns.get(c)));
            }
            row[c] = GridRender.formatCell(currentColumns[c], inChunk, columnDtypes.get(c));
        }
        return row;
    }

    private String[] emptyRow() {
        return new String[columns.size()];
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            // Bounded: a load stalled on the network must not keep `q` from quitting. The cleanup
            // stays queued and runs when the worker gets to it.
            worker.runAndAwait(() -> {
                closeCurrentChunk();
                if (iter != null) {
                    iter.close();
                    iter = null;
                }
            }, IoWorker.TEARDOWN_WAIT);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
