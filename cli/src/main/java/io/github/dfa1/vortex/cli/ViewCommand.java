package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.cli.tui.IoWorker;
import io.github.dfa1.vortex.cli.tui.VortexGridTui;
import io.github.dfa1.vortex.cli.tui.VortexGridTui.GridData;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.VortexHttpReader;
import io.github.dfa1.vortex.reader.VortexReader;
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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class ViewCommand {

    private static final long DEFAULT_ROW_CAP = 100_000L;

    private ViewCommand() {
    }

    static int run(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: view <file.vortex | http(s)://url>");
            return ExitStatus.USAGE_ERROR;
        }
        try (IoWorker worker = new IoWorker("vortex-view-io")) {
            VortexHandle handle = openOnWorker(worker, args[1]);
            if (handle == null) {
                return ExitStatus.FILE_NOT_FOUND;
            }
            try {
                GridData data = loadOnWorker(worker, handle, args[1]);
                VortexGridTui.show(data);
            } finally {
                closeOnWorker(worker, handle);
            }
            return ExitStatus.OK;
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("error: " + describe(e));
            return ExitStatus.ERROR;
        }
    }

    private static VortexHandle openOnWorker(IoWorker worker, String target)
            throws InterruptedException, IOException {
        AtomicReference<VortexHandle> handle = new AtomicReference<>();
        AtomicReference<IOException> failure = new AtomicReference<>();
        worker.runAndAwait(() -> {
            try {
                handle.set(open(target));
            } catch (IOException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return handle.get();
    }

    private static void closeOnWorker(IoWorker worker, VortexHandle handle) {
        try {
            worker.runAndAwait(handle::close);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static VortexHandle open(String target) throws IOException {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            try {
                return VortexHttpReader.open(new URI(target));
            } catch (URISyntaxException e) {
                System.err.println("invalid URL: " + target);
                return null;
            }
        }
        Path path = Path.of(target);
        if (!Files.exists(path)) {
            System.err.println("file not found: " + path);
            return null;
        }
        return VortexReader.open(path);
    }

    private static GridData loadOnWorker(IoWorker worker, VortexHandle handle, String source)
            throws InterruptedException {
        AtomicReference<GridData> ref = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        worker.runAndAwait(() -> {
            try {
                ref.set(load(handle, source));
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return ref.get();
    }

    private static GridData load(VortexHandle handle, String source) {
        if (!(handle.dtype() instanceof DType.Struct schema)) {
            throw new VortexException("view requires struct root dtype, got " + handle.dtype());
        }
        List<String> columns = schema.fieldNames();
        int colCount = columns.size();

        long cap = rowCap();
        List<String[]> rows = new ArrayList<>();
        long totalRows = 0;
        boolean truncated = false;

        try (ScanIterator iter = handle.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                try (Chunk chunk = iter.next()) {
                    long chunkRows = chunk.rowCount();
                    totalRows += chunkRows;
                    if (rows.size() >= cap) {
                        truncated = true;
                        continue;
                    }
                    Array[] arrays = new Array[colCount];
                    for (int c = 0; c < colCount; c++) {
                        arrays[c] = chunk.column(columns.get(c));
                    }
                    long take = Math.min(chunkRows, cap - rows.size());
                    for (long r = 0; r < take; r++) {
                        String[] row = new String[colCount];
                        for (int c = 0; c < colCount; c++) {
                            row[c] = formatCell(arrays[c], r);
                        }
                        rows.add(row);
                    }
                    if (rows.size() >= cap && take < chunkRows) {
                        truncated = true;
                    }
                }
            }
        }
        return new GridData(source, columns, rows, totalRows, truncated);
    }

    private static long rowCap() {
        String prop = System.getProperty("vortex.view.rowcap");
        if (prop != null) {
            try {
                long v = Long.parseLong(prop);
                if (v > 0) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_ROW_CAP;
    }

    private static String formatCell(Array array, long i) {
        Array inner = array instanceof MaskedArray m ? m.inner() : array;
        if (array instanceof MaskedArray m && !m.isValid(i)) {
            return "";
        }
        try {
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
            return "<error>";
        }
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

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        while (cur != null) {
            if (!sb.isEmpty()) {
                sb.append(" -> ");
            }
            sb.append(cur.getClass().getSimpleName());
            if (cur.getMessage() != null) {
                sb.append(": ").append(cur.getMessage());
            }
            cur = cur.getCause();
        }
        return sb.toString();
    }
}
