package io.github.dfa1.vortex.csv;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.VortexWriter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        try (CsvReader<CsvRecord> reader = csvReader(csvPath, options)) {
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
        try (InputStream body = response.body();
             CsvReader<CsvRecord> reader = CsvReader.builder()
                                                    .fieldSeparator(options.delimiter())
                                                    .ofCsvRecord(body)) {
            importCsv(reader, vortexPath, options);
        }
    }

    private static void importCsv(CsvReader<CsvRecord> reader, Path vortexPath, ImportOptions options)
            throws IOException {
        int chunkSize = options.chunkSize();

        // Read header row (if present) and buffer the first chunk of data rows.
        // Both happen in a single pass so the reader position advances correctly.
        String[] headers = null;
        List<String[]> firstChunk = new ArrayList<>(chunkSize);
        boolean expectHeader = options.hasHeader();

        for (CsvRecord csvRecord : reader) {
            if (expectHeader) {
                headers = csvRecord.getFields().toArray(String[]::new);
                expectHeader = false;
            } else {
                firstChunk.add(csvRecord.getFields().toArray(String[]::new));
                if (firstChunk.size() == chunkSize) {
                    break;
                }
            }
        }

        if (firstChunk.isEmpty()) {
            throw new IllegalArgumentException("CSV file has no data rows");
        }

        // Generate synthetic column names when the file has no header row.
        if (headers == null) {
            headers = generateHeaders(firstChunk.getFirst().length);
        }

        DType.Struct schema = options.schema() != null
                ? options.schema()
                : inferSchemaFromRows(headers, firstChunk);

        try (FileChannel channel = FileChannel.open(
                vortexPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(channel, schema, options.writeOptions())) {

            long totalRows = 0;
            long lastReported = 0;

            // Write the buffered first chunk.
            writer.writeChunk(buildChunk(schema, firstChunk));
            totalRows += firstChunk.size();
            lastReported = totalRows;
            reportProgress(options, totalRows);
            firstChunk.clear();

            // Stream the rest of the file through the still-open reader.
            List<String[]> chunk = new ArrayList<>(chunkSize);
            for (CsvRecord csvRecord : reader) {
                chunk.add(csvRecord.getFields().toArray(String[]::new));
                totalRows++;
                if (chunk.size() == chunkSize) {
                    writer.writeChunk(buildChunk(schema, chunk));
                    chunk.clear();
                }
                if (totalRows - lastReported >= PROGRESS_BATCH) {
                    reportProgress(options, totalRows);
                    lastReported = totalRows;
                }
            }
            if (!chunk.isEmpty()) {
                writer.writeChunk(buildChunk(schema, chunk));
            }
            if (totalRows > lastReported) {
                reportProgress(options, totalRows);
            }
        }
    }

    private static DType.Struct inferSchemaFromRows(String[] headers, List<String[]> rows) {
        int colCount = headers.length;
        boolean[] canBeLong = new boolean[colCount];
        boolean[] canBeDouble = new boolean[colCount];
        boolean[] canBeBool = new boolean[colCount];
        Arrays.fill(canBeLong, true);
        Arrays.fill(canBeDouble, true);
        Arrays.fill(canBeBool, true);

        for (String[] row : rows) {
            for (int c = 0; c < colCount; c++) {
                probeCell(canBeLong, canBeDouble, canBeBool, c, safeGet(row, c));
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

    private static CsvReader<CsvRecord> csvReader(Path path, ImportOptions options) throws IOException {
        return CsvReader.builder()
                        .fieldSeparator(options.delimiter())
                        .ofCsvRecord(path);
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

    static Map<ColumnName, Object> buildChunk(DType.Struct schema, List<String[]> rows) {
        int n = rows.size();
        Map<ColumnName, Object> chunk = new LinkedHashMap<>();
        for (int c = 0; c < schema.fieldNames().size(); c++) {
            chunk.put(schema.fieldNames().get(c), buildColumn(schema.fieldTypes().get(c), rows, c, n));
        }
        return chunk;
    }

    private static Object buildColumn(DType dtype, List<String[]> rows, int colIdx, int n) {
        return switch (dtype) {
            case DType.Primitive p when p.ptype() == PType.I64 -> {
                long[] arr = new long[n];
                for (int i = 0; i < n; i++) {
                    String v = safeGet(rows.get(i), colIdx);
                    arr[i] = v.isEmpty() ? 0L : Long.parseLong(v);
                }
                yield arr;
            }
            case DType.Primitive p when p.ptype() == PType.F64 -> {
                double[] arr = new double[n];
                for (int i = 0; i < n; i++) {
                    String v = safeGet(rows.get(i), colIdx);
                    arr[i] = v.isEmpty() ? 0.0 : Double.parseDouble(v);
                }
                yield arr;
            }
            case DType.Bool _ -> {
                boolean[] arr = new boolean[n];
                for (int i = 0; i < n; i++) {
                    arr[i] = Boolean.parseBoolean(safeGet(rows.get(i), colIdx));
                }
                yield arr;
            }
            case DType.Utf8 _ -> {
                String[] arr = new String[n];
                for (int i = 0; i < n; i++) {
                    arr[i] = safeGet(rows.get(i), colIdx);
                }
                yield arr;
            }
            default -> throw new UnsupportedOperationException("unsupported dtype for CSV import: " + dtype);
        };
    }

    private static String safeGet(String[] row, int idx) {
        if (idx >= row.length || row[idx] == null) {
            return "";
        }
        return row[idx];
    }
}
