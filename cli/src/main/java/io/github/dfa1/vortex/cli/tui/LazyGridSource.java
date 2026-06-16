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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/// Streaming row source backing the [VortexGridTui].
///
/// Decodes vortex chunks on demand as the grid viewport scrolls into them.
/// Chunk boundaries are derived once up front from [ScanIterator#chunkRowCounts]
/// so navigation math can find the chunk for any absolute row in O(log chunks).
///
/// A fixed-size LRU cache keeps the most recently touched chunks decoded as
/// `String[][]` row arrays. When the viewport touches a non-resident chunk,
/// the source advances its `ScanIterator` forward until the target chunk is
/// decoded; if the target sits before the current iterator position the
/// iterator is closed and reopened from the start of the file.
///
/// All `VortexHandle` I/O is routed through the provided [IoWorker] so the
/// confined `Arena` owning the handle is never crossed from the render thread.
public final class LazyGridSource implements AutoCloseable {

    /// Number of chunks kept decoded in memory at once.
    public static final int DEFAULT_CACHE_CAPACITY = 2;

    private final VortexHandle handle;
    private final IoWorker worker;
    private final List<String> columns;
    private final List<DType> columnDtypes;
    private final long totalRows;
    private final int chunkCount;
    private final long[] chunkStartRows;
    private final long[] chunkRowCounts;
    private final int cacheCap;
    private final LinkedHashMap<Integer, String[][]> cache;

    private ScanIterator iter;
    private int iterNextChunk;
    private boolean closed;

    /// Builds a lazy source over `handle`, computing chunk boundaries up front.
    ///
    /// @param handle  open Vortex file handle owned by `worker`
    /// @param worker  I/O dispatcher for the handle's confined thread
    /// @param cacheCap maximum decoded chunks kept resident; `<= 0` falls back
    ///                  to {@link #DEFAULT_CACHE_CAPACITY}
    /// @return initialised source
    /// @throws InterruptedException if the calling thread is interrupted while
    ///                              waiting for the worker
    public static LazyGridSource open(VortexHandle handle, IoWorker worker, int cacheCap)
            throws InterruptedException {
        return new LazyGridSource(handle, worker, cacheCap);
    }

    private LazyGridSource(VortexHandle handle, IoWorker worker, int cacheCap)
            throws InterruptedException {
        this.handle = handle;
        this.worker = worker;
        this.cacheCap = cacheCap > 0 ? cacheCap : DEFAULT_CACHE_CAPACITY;
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

        this.cache = new LinkedHashMap<>(this.cacheCap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, String[][]> eldest) {
                return size() > LazyGridSource.this.cacheCap;
            }
        };
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

    /// Decoded row at absolute index `absRow`. Triggers a chunk decode if the
    /// chunk containing this row is not currently in the cache.
    ///
    /// @param absRow row index in `[0, totalRows())`
    /// @return one cell per column
    /// @throws InterruptedException if the calling thread is interrupted while
    ///                              waiting for the worker
    /// @throws VortexException      if `absRow` is out of bounds or this source
    ///                              has already been closed
    public String[] row(long absRow) throws InterruptedException {
        if (closed) {
            throw new VortexException("LazyGridSource closed");
        }
        if (absRow < 0 || absRow >= totalRows) {
            throw new VortexException("row index out of bounds: " + absRow);
        }
        int chunkIdx = findChunk(absRow);
        long inChunk = absRow - chunkStartRows[chunkIdx];
        String[][] decoded = ensureLoaded(chunkIdx);
        return decoded[(int) inChunk];
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

    private String[][] ensureLoaded(int chunkIdx) throws InterruptedException {
        String[][] hit = cache.get(chunkIdx);
        if (hit != null) {
            return hit;
        }
        if (chunkIdx < iterNextChunk) {
            reopenIter();
        }
        advanceTo(chunkIdx);
        String[][] loaded = cache.get(chunkIdx);
        if (loaded == null) {
            throw new VortexException("failed to decode chunk " + chunkIdx);
        }
        return loaded;
    }

    private void advanceTo(int targetChunkIdx) throws InterruptedException {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        worker.runAndAwait(() -> {
            try {
                while (iterNextChunk <= targetChunkIdx && iter.hasNext()) {
                    int loading = iterNextChunk;
                    try (Chunk chunk = iter.next()) {
                        String[][] formatted = formatChunkRows(chunk);
                        cache.put(loading, formatted);
                    }
                    iterNextChunk++;
                }
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private void reopenIter() throws InterruptedException {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        worker.runAndAwait(() -> {
            try {
                if (iter != null) {
                    iter.close();
                }
                iter = handle.scan(ScanOptions.all());
                iterNextChunk = 0;
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private String[][] formatChunkRows(Chunk chunk) {
        int colCount = columns.size();
        Array[] arrays = new Array[colCount];
        for (int c = 0; c < colCount; c++) {
            arrays[c] = chunk.column(columns.get(c));
        }
        int rows = (int) chunk.rowCount();
        String[][] out = new String[rows][];
        for (int r = 0; r < rows; r++) {
            String[] row = new String[colCount];
            for (int c = 0; c < colCount; c++) {
                row[c] = formatCell(arrays[c], r, columnDtypes.get(c));
            }
            out[r] = row;
        }
        return out;
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
                if (iter != null) {
                    iter.close();
                    iter = null;
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cache.clear();
    }
}
