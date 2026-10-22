package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.compute.Utf8Validation;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.VortexWriter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.nio.charset.StandardCharsets.UTF_8;

/// Parses a CSV file and writes a Vortex file.
///
/// Column types are inferred in priority order: long → double → boolean → utf8.
/// Provide a schema via [ImportOptions#withSchema] to skip inference.
/// Empty cell values are treated as 0 / false / "" for typed columns.
///
/// Import is single-pass streaming. The first [ImportOptions#chunkSize()] data rows
/// are buffered to infer the schema (or skipped when a schema is provided), then
/// all rows — including those first rows — are written in chunks. Memory usage is
/// O(chunkSize) regardless of file size.
public final class CsvImporter {

    private static final long PROGRESS_BATCH = 10_000;

    /// Shared across all instances, mirroring `VortexHttpReader`'s `HttpClient` reuse rationale:
    /// the JDK client is heavyweight and designed for reuse. Never closed: lifetime tracks the JVM.
    ///
    /// Package-private and non-final purely as a unit-test seam: tests substitute a mocked
    /// client to drive [#importCsv(URI, Path, ImportOptions)] without real network I/O.
    /// Production code never reassigns it.
    static HttpClient httpClient = HttpClient.newHttpClient();

    private CsvImporter() {
    }

    /// Imports a CSV file to a Vortex file using default options.
    ///
    /// @param csvPath    path to the source CSV file
    /// @param vortexPath path to write the output Vortex file
    /// @throws IOException if reading or writing fails
    public static void importCsv(Path csvPath, Path vortexPath) throws IOException {
        importCsv(csvPath, vortexPath, ImportOptions.defaults());
    }

    /// Imports a CSV file to a Vortex file.
    ///
    /// The file is read exactly once. The first chunk of rows is buffered for schema
    /// inference (O(chunkSize) memory); remaining rows stream directly to the writer.
    /// Progress is reported via [ProgressListener#onProgress(long, long)] with
    /// `rowsTotal = -1` (total unknown) after each chunk completes.
    ///
    /// @param csvPath    path to the source CSV file
    /// @param vortexPath path to write the output Vortex file
    /// @param options    import configuration
    /// @throws IOException              if reading or writing fails
    /// @throws IllegalArgumentException if the CSV file has no data rows
    public static void importCsv(Path csvPath, Path vortexPath, ImportOptions options) throws IOException {
        try (CsvByteReader reader = new CsvByteReader(Files.newInputStream(csvPath), options.delimiter())) {
            importCsv(reader, vortexPath, options);
        }
    }

    /// Imports a CSV file served over HTTP(S) to a Vortex file using default options.
    ///
    /// @param csvUri     the `http(s)://` URL of the source CSV file
    /// @param vortexPath path to write the output Vortex file
    /// @throws IOException if reading or writing fails
    public static void importCsv(URI csvUri, Path vortexPath) throws IOException {
        importCsv(csvUri, vortexPath, ImportOptions.defaults());
    }

    /// Imports a CSV file served over HTTP(S) to a Vortex file.
    ///
    /// Unlike Parquet's random-access, footer-first format, CSV is read front to back in one
    /// streaming pass, so the response body is consumed directly as it arrives — no Range
    /// requests, no local temp file, and no full-file buffering.
    ///
    /// @param csvUri     the `http(s)://` URL of the source CSV file
    /// @param vortexPath path to write the output Vortex file
    /// @param options    import configuration
    /// @throws IOException              if fetching `csvUri` or writing `vortexPath` fails
    /// @throws IllegalArgumentException if the CSV file has no data rows
    public static void importCsv(URI csvUri, Path vortexPath, ImportOptions options) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(csvUri).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching " + csvUri, e);
        }
        if (response.statusCode() != 200) {
            IOException failure = new IOException("HTTP " + response.statusCode() + " fetching " + csvUri);
            // Close the still-open body stream before throwing: with the streaming
            // ofInputStream() handler, the JDK HttpClient only releases the underlying
            // connection once the body is consumed or closed. A failure closing it is
            // secondary to the HTTP status that's already failing the call, so it's
            // attached as suppressed rather than replacing the real error.
            try {
                response.body().close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        try (CsvByteReader reader = new CsvByteReader(response.body(), options.delimiter())) {
            importCsv(reader, vortexPath, options);
        }
    }

    /// The buffered first chunk of data rows (used for schema inference) plus the resolved
    /// header names and schema, produced by [#readFirstChunk].
    private record FirstChunk(ColumnChunk rows, DType.Struct schema) {
    }

    private static void importCsv(CsvByteReader reader, Path vortexPath, ImportOptions options)
            throws IOException {
        FirstChunk firstChunk = readFirstChunk(reader, options);
        DType.Struct schema = firstChunk.schema();

        // Closed in reverse: the chunk writer drains its last chunk before the Vortex writer closes.
        try (FileChannel channel = FileChannel.open(
                vortexPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(channel, schema, options.writeOptions());
             ChunkWriter chunkWriter = new ChunkWriter(writer, schema, options.writeOptions().executor())) {
            // firstChunk isn't touched again after this call, so its buffered rows (up to
            // chunkSize row arrays) become collectible once written, rather than staying
            // reachable for the rest of a potentially large import.
            long totalRows = writeFirstChunk(chunkWriter, firstChunk, options);
            streamRemainingChunks(reader, chunkWriter, options, totalRows);
            chunkWriter.await();
        }
    }

    /// Converts and writes each chunk on one background thread while the caller parses the next.
    ///
    /// Parsing is single-threaded and the import's critical path: converting a chunk's text to
    /// columns and handing it to the writer used to stall it for about a third of a large import
    /// while the encoding threads sat partly idle. One chunk is in flight at a time, so chunks are
    /// written in file order and memory stays at two chunks of rows.
    private static final class ChunkWriter implements AutoCloseable {

        private final VortexWriter writer;
        private final DType.Struct schema;
        private final Executor columnExecutor;
        private final ExecutorService thread = Executors.newSingleThreadExecutor();
        private Future<?> pending = CompletableFuture.completedFuture(null);

        ChunkWriter(VortexWriter writer, DType.Struct schema, Executor columnExecutor) {
            this.writer = writer;
            this.schema = schema;
            this.columnExecutor = columnExecutor;
        }

        /// Waits for the previous chunk, then starts writing `rows`.
        void submit(ColumnChunk rows) throws IOException {
            await();
            pending = thread.submit(() -> {
                writer.writeChunk(buildChunk(schema, rows, columnExecutor));
                return null;
            });
        }

        /// Waits for the chunk in flight, rethrowing its failure.
        void await() throws IOException {
            try {
                pending.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted writing a chunk", e);
            } catch (ExecutionException e) {
                // The column conversion runs on futures, so its failure arrives wrapped once more.
                Throwable cause = e.getCause();
                if (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof IOException io) {
                    throw io;
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IOException(cause);
            }
        }

        /// Waits for the chunk in flight (without rethrowing: the caller is already failing or has
        /// awaited it) so the Vortex writer is never closed under a running write.
        @Override
        public void close() {
            thread.close();
        }
    }

    /// Reads the header row (if present) and buffers the first [ImportOptions#chunkSize()] data
    /// rows in the same pass, so the reader position advances correctly; infers the schema from
    /// those buffered rows unless [ImportOptions#schema()] already gives one.
    ///
    /// @throws IllegalArgumentException if the CSV file has no data rows
    private static FirstChunk readFirstChunk(CsvByteReader reader, ImportOptions options) throws IOException {
        String[] headers = null;
        if (options.header()) {
            ColumnChunk header = new ColumnChunk(1);
            if (reader.readRecord(header)) {
                headers = new String[header.columns()];
                for (int c = 0; c < headers.length; c++) {
                    headers[c] = header.text(c, 0);
                }
            }
        }
        ColumnChunk rows = new ColumnChunk(options.chunkSize());
        while (!rows.isFull() && reader.readRecord(rows)) {
            // fill the first chunk
        }

        if (rows.rows() == 0) {
            throw new IllegalArgumentException("CSV file has no data rows");
        }

        // Generate synthetic column names when the file has no header row.
        if (headers == null) {
            headers = generateHeaders(rows.columns());
        }

        DType.Struct schema = options.schema() != null
                ? options.schema()
                : inferSchemaFromRows(headers, rows);
        return new FirstChunk(rows, schema);
    }

    /// Writes the buffered first chunk and reports progress once.
    ///
    /// @return the row count written so far (the first chunk's size)
    private static long writeFirstChunk(ChunkWriter chunkWriter, FirstChunk firstChunk, ImportOptions options)
            throws IOException {
        chunkWriter.submit(firstChunk.rows());
        long totalRows = firstChunk.rows().rows();
        reportProgress(options, totalRows);
        return totalRows;
    }

    /// Streams the rest of the file through the still-open `reader`, writing a chunk every
    /// [ImportOptions#chunkSize()] rows and reporting progress every [#PROGRESS_BATCH] rows.
    private static void streamRemainingChunks(CsvByteReader reader, ChunkWriter chunkWriter,
            ImportOptions options, long rowsSoFar) throws IOException {
        int chunkSize = options.chunkSize();
        long totalRows = rowsSoFar;
        long lastReported = totalRows;
        ColumnChunk chunk = new ColumnChunk(chunkSize);
        while (reader.readRecord(chunk)) {
            totalRows++;
            if (chunk.isFull()) {
                chunkWriter.submit(chunk);
                chunk = new ColumnChunk(chunkSize);
            }
            if (totalRows - lastReported >= PROGRESS_BATCH) {
                reportProgress(options, totalRows);
                lastReported = totalRows;
            }
        }
        if (chunk.rows() > 0) {
            chunkWriter.submit(chunk);
        }
        if (totalRows > lastReported) {
            reportProgress(options, totalRows);
        }
    }

    private static DType.Struct inferSchemaFromRows(String[] headers, ColumnChunk rows) {
        int colCount = headers.length;
        boolean[] canBeLong = new boolean[colCount];
        boolean[] canBeDouble = new boolean[colCount];
        boolean[] canBeBool = new boolean[colCount];
        Arrays.fill(canBeLong, true);
        Arrays.fill(canBeDouble, true);
        Arrays.fill(canBeBool, true);

        for (int row = 0; row < rows.rows(); row++) {
            for (int c = 0; c < colCount; c++) {
                probeCell(canBeLong, canBeDouble, canBeBool, c, rows.text(c, row));
            }
        }

        List<ColumnName> names = Arrays.stream(headers).map(ColumnName::of).toList();
        List<DType> types = new ArrayList<>(colCount);
        for (int c = 0; c < colCount; c++) {
            types.add(resolveType(canBeLong[c], canBeDouble[c], canBeBool[c]));
        }
        return new DType.Struct(names, types, false);
    }

    private static void probeCell(boolean[] canBeLong, boolean[] canBeDouble, boolean[] canBeBool,
            int c, String val) {
        if (val.isEmpty()) {
            return;
        }
        if (canBeLong[c]) {
            try {
                Long.parseLong(val);
            } catch (NumberFormatException _) {
                canBeLong[c] = false;
            }
        }
        if (canBeDouble[c]) {
            try {
                Double.parseDouble(val);
            } catch (NumberFormatException _) {
                canBeDouble[c] = false;
            }
        }
        if (canBeBool[c] && !val.equalsIgnoreCase("true") && !val.equalsIgnoreCase("false")) {
            canBeBool[c] = false;
        }
    }

    private static void reportProgress(ImportOptions options, long totalRows) {
        if (options.progressListener() != null) {
            options.progressListener().onProgress(totalRows, -1);
        }
    }

    private static String[] generateHeaders(int colCount) {
        String[] headers = new String[colCount];
        for (int i = 0; i < colCount; i++) {
            headers[i] = "col" + i;
        }
        return headers;
    }

    private static DType resolveType(boolean canBeLong, boolean canBeDouble, boolean canBeBool) {
        if (canBeLong) {
            return DType.I64;
        }
        if (canBeDouble) {
            return DType.F64;
        }
        if (canBeBool) {
            return DType.BOOL;
        }
        return DType.UTF8;
    }

    /// Converts the text of each column on `executor`: the columns are independent, and with the
    /// parse this was the caller thread's work ahead of the (parallel) compression.
    static Map<ColumnName, Object> buildChunk(DType.Struct schema, ColumnChunk rows, Executor executor) {
        List<CompletableFuture<Object>> columns = new ArrayList<>();
        for (int c = 0; c < schema.fieldNames().size(); c++) {
            DType dtype = schema.fieldTypes().get(c);
            int column = c;
            columns.add(CompletableFuture.supplyAsync(() -> buildColumn(dtype, rows, column), executor));
        }
        Map<ColumnName, Object> chunk = new LinkedHashMap<>();
        for (int c = 0; c < columns.size(); c++) {
            chunk.put(schema.fieldNames().get(c), columns.get(c).join());
        }
        return chunk;
    }

    /// Converts one column from its bytes, as the `String` parsers would convert its text: plain
    /// ASCII numbers are parsed in place, anything else falls back to `Long.parseLong` and friends
    /// on the decoded text, so their results, and their errors, are unchanged. A column the file
    /// lacks (a schema with more fields than the CSV) is all empty fields.
    private static Object buildColumn(DType dtype, ColumnChunk rows, int column) {
        int n = rows.rows();
        byte[] bytes = column < rows.columns() ? rows.bytes(column) : null;
        return switch (dtype) {
            case DType.Primitive p when p.ptype() == PType.I64 -> {
                long[] arr = new long[n];
                for (int i = 0; bytes != null && i < n; i++) {
                    arr[i] = parseLong(bytes, rows.start(column, i), rows.end(column, i));
                }
                yield arr;
            }
            case DType.Primitive p when p.ptype() == PType.F64 -> {
                double[] arr = new double[n];
                for (int i = 0; bytes != null && i < n; i++) {
                    arr[i] = parseDouble(bytes, rows.start(column, i), rows.end(column, i));
                }
                yield arr;
            }
            case DType.Bool _ -> {
                boolean[] arr = new boolean[n];
                for (int i = 0; bytes != null && i < n; i++) {
                    arr[i] = parseBoolean(bytes, rows.start(column, i), rows.end(column, i));
                }
                yield arr;
            }
            case DType.Utf8 _ -> {
                byte[][] arr = new byte[n][];
                // one word-at-a-time pass over the column instead of a check per field: CSV text
                // is usually all ASCII, which is always well-formed UTF-8
                boolean ascii = bytes == null || n == 0 || isAscii(bytes, 0, rows.end(column, n - 1));
                for (int i = 0; i < n; i++) {
                    if (bytes == null) {
                        arr[i] = EMPTY;
                    } else if (ascii) {
                        arr[i] = Arrays.copyOfRange(bytes, rows.start(column, i), rows.end(column, i));
                    } else {
                        arr[i] = utf8(bytes, rows.start(column, i), rows.end(column, i));
                    }
                }
                yield arr;
            }
            default -> throw new UnsupportedOperationException("unsupported dtype for CSV import: " + dtype);
        };
    }

    private static final byte[] EMPTY = new byte[0];

    /// `Long.parseLong` on the field's text, `0` for an empty field. Up to 18 ASCII digits cannot
    /// overflow a long, so they are summed in place; anything else takes the `String` path.
    static long parseLong(byte[] b, int from, int to) {
        if (from == to) {
            return 0L;
        }
        int i = from;
        boolean negative = b[i] == '-';
        if (negative || b[i] == '+') {
            i++;
        }
        if (i == to || to - i > 18) {
            return Long.parseLong(new String(b, from, to - from, UTF_8));
        }
        long value = 0;
        for (; i < to; i++) {
            int digit = b[i] - '0';
            if (digit < 0 || digit > 9) {
                return Long.parseLong(new String(b, from, to - from, UTF_8));
            }
            value = value * 10 + digit;
        }
        return negative ? -value : value;
    }

    /// `Boolean.parseBoolean` on the field's text: `true` ignoring ASCII case. Text is compared
    /// only when it could match, i.e. when it is 4 bytes, or longer and not ASCII (a non-ASCII
    /// character could case-fold to one of `true`'s letters).
    static boolean parseBoolean(byte[] b, int from, int to) {
        int length = to - from;
        if (length == 4) {
            return (b[from] | 0x20) == 't' && (b[from + 1] | 0x20) == 'r'
                    && (b[from + 2] | 0x20) == 'u' && (b[from + 3] | 0x20) == 'e';
        }
        if (length > 4 && !isAscii(b, from, to)) {
            return Boolean.parseBoolean(new String(b, from, length, UTF_8));
        }
        return false;
    }

    /// The field's bytes as a Utf8 value: copied when well-formed UTF-8, else decoded and encoded
    /// back, which turns each malformed sequence into U+FFFD as FastCSV's decoding did.
    static byte[] utf8(byte[] b, int from, int to) {
        if (from == to) {
            return EMPTY;
        }
        if (isAscii(b, from, to) || Utf8Validation.isWellFormed(b, from, to)) {
            return Arrays.copyOfRange(b, from, to);
        }
        return new String(b, from, to - from, UTF_8).getBytes(UTF_8);
    }

    /// Whether no byte of `b[from, to)` has its high bit set, eight bytes at a time.
    static boolean isAscii(byte[] b, int from, int to) {
        int i = from;
        long high = 0;
        for (; i + Long.BYTES <= to; i += Long.BYTES) {
            high |= (long) LONGS.get(b, i);
        }
        for (; i < to; i++) {
            high |= b[i];
        }
        return (high & 0x8080_8080_8080_8080L) == 0;
    }

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /// Powers of ten a double holds exactly: 10^22 is the last.
    private static final double[] EXACT_POWERS_OF_TEN = {
            1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
            1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};

    /// `Double.parseDouble` on the field's text, `0.0` for an empty field.
    ///
    /// A plain decimal (`-12.345`) whose digits fit 2^53 is computed as `digits / 10^fractionDigits`:
    /// both operands are exact doubles and IEEE division rounds correctly, so the result is the
    /// correctly rounded value `parseDouble` returns (Clinger's fast path). Anything else (an
    /// exponent, more digits, `NaN`, spaces, a bare `.5`) takes `parseDouble` itself.
    static double parseDouble(byte[] b, int from, int to) {
        if (from == to) {
            return 0.0;
        }
        int i = from;
        boolean negative = b[i] == '-';
        if (negative || b[i] == '+') {
            i++;
        }
        long digits = 0;
        int count = 0;
        int fraction = -1;
        for (; i < to; i++) {
            int c = b[i];
            if (c == '.' && fraction < 0 && count > 0) {
                fraction = 0;
                continue;
            }
            int digit = c - '0';
            if (digit < 0 || digit > 9 || ++count > 18) {
                return Double.parseDouble(new String(b, from, to - from, UTF_8));
            }
            digits = digits * 10 + digit;
            if (fraction >= 0) {
                fraction++;
            }
        }
        if (count == 0 || fraction == 0 || fraction > 22 || digits > (1L << 53)) {
            return Double.parseDouble(new String(b, from, to - from, UTF_8));
        }
        double value = fraction > 0 ? digits / EXACT_POWERS_OF_TEN[fraction] : digits;
        return negative ? -value : value;
    }
}
