package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyDecimalArray;
import io.github.dfa1.vortex.reader.array.LazyDecimalBytePartsArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.extension.DateExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.TimeExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.TimestampExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.UuidExtensionDecoder;

import java.util.List;
import java.util.Optional;
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
    /// @return initialised source
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
        this.columns = schema.fieldNames();
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
            try (Chunk discard = iter.next()) {
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
            row[c] = formatCell(arrays[c], inChunk, columnDtypes.get(c));
        }
        return row;
    }

    private String[] emptyRow() {
        return new String[columns.size()];
    }

    private static String formatCell(Array array, long i, DType declared) {
        if (array == null || i >= array.length()) {
            return "";
        }
        if (array instanceof MaskedArray m && !m.isValid(i)) {
            return "";
        }
        Array inner = array instanceof MaskedArray m ? m.inner() : array;
        if (i >= inner.length()) {
            return "";
        }
        try {
            if (declared instanceof DType.Extension ext) {
                Optional<String> extFormatted = formatExtension(ext, inner, i);
                if (extFormatted.isPresent()) {
                    return extFormatted.get();
                }
            }
            return switch (inner) {
                case LongArray a -> Long.toString(a.getLong(i));
                case IntArray a -> Integer.toString(a.getInt(i));
                case ShortArray a -> Short.toString(a.getShort(i));
                case ByteArray a -> Byte.toString(a.getByte(i));
                case DoubleArray a -> Double.toString(a.getDouble(i));
                case FloatArray a -> Float.toString(a.getFloat(i));
                case BoolArray a -> Boolean.toString(a.getBoolean(i));
                case VarBinArray a -> a.dtype() instanceof DType.Utf8
                        ? a.getString(i)
                        : bytesToHex(a.getBytes(i));
                case LazyDecimalArray a -> a.getDecimal(i).toPlainString();
                case LazyDecimalBytePartsArray a -> a.getDecimal(i).toPlainString();
                default -> "<" + inner.getClass().getSimpleName() + ">";
            };
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            return "<" + e.getClass().getSimpleName()
                    + (msg != null ? ": " + msg.split("\n", 2)[0] : "") + ">";
        }
    }

    private static Optional<String> formatExtension(DType.Extension ext, Array storage, long i) {
        Optional<ExtensionId> idOpt = ExtensionId.parse(ext.extensionId());
        if (idOpt.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(switch (idOpt.get()) {
            case VORTEX_DATE -> DateExtensionDecoder.INSTANCE.decode(storage, i).toString();
            case VORTEX_TIME -> TimeExtensionDecoder.INSTANCE.decode(ext, storage, i).toString();
            case VORTEX_TIMESTAMP -> TimestampExtensionDecoder.INSTANCE.instant(ext, storage, i).toString();
            case VORTEX_UUID -> UuidExtensionDecoder.INSTANCE.decode(storage, i).toString();
        });
    }

    private static String bytesToHex(byte[] bytes) {
        int n = Math.min(bytes.length, 16);
        StringBuilder sb = new StringBuilder(n * 2 + 2);
        sb.append("0x");
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", bytes[i] & 0xff));
        }
        if (bytes.length > n) {
            sb.append("...");
        }
        return sb.toString();
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
