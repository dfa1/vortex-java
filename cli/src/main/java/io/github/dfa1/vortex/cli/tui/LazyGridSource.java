package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.array.Array;

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

    private final VortexHandle handle;
    private final IoWorker worker;
    private final List<String> columns;
    private final List<DType> columnDtypes;
    private final long totalRows;
    private final int chunkCount;
    private final long[] chunkStartRows;
    private final long[] chunkRowCounts;

    private ScanIterator iter;
    private int iterNextChunk;
    private Chunk currentChunk;
    private int currentChunkIdx = -1;
    private Array[] currentColumns;
    private boolean closed;

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
        this.chunkRowCounts = rowCountsRef.get();
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
                fillWindow(out, startAbsRow, count);
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

    private void fillWindow(String[][] out, long startAbsRow, int count) {
        long row = startAbsRow;
        int slot = 0;
        while (slot < count) {
            if (row >= totalRows) {
                out[slot++] = emptyRow();
                continue;
            }
            int chunkIdx = findChunk(row);
            loadChunk(chunkIdx);
            long chunkStart = chunkStartRows[chunkIdx];
            long chunkEnd = chunkStartRows[chunkIdx + 1];
            long limit = Math.min(chunkEnd, startAbsRow + count);
            while (row < limit && slot < count) {
                out[slot++] = formatRow(currentColumns, row - chunkStart);
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
        for (int c = 0; c < columns.size(); c++) {
            currentColumns[c] = chunk.column(columns.get(c));
        }
    }

    private void closeCurrentChunk() {
        if (currentChunk != null) {
            currentChunk.close();
            currentChunk = null;
            currentChunkIdx = -1;
            currentColumns = null;
        }
    }

    private String[] formatRow(Array[] arrays, long inChunk) {
        int n = columns.size();
        String[] row = new String[n];
        for (int c = 0; c < n; c++) {
            row[c] = GridRender.formatCell(arrays[c], inChunk, columnDtypes.get(c));
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
            worker.runAndAwait(() -> {
                closeCurrentChunk();
                if (iter != null) {
                    iter.close();
                    iter = null;
                }
            });
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
