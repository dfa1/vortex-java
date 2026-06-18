package io.github.dfa1.vortex.csv;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.VortexWriter;

import java.io.IOException;
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
/// Import is streaming: rows are read and written in chunks of [ImportOptions#chunkSize]
/// without loading the entire file into memory. Schema inference requires a first
/// sequential pass over the file; writing is a second pass. When a schema is provided
/// via [ImportOptions#withSchema] only one pass is needed.
public final class CsvImporter {

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
    /// Rows are streamed in chunks — the file is never fully loaded into memory.
    /// If no schema is provided, a first streaming pass infers column types; a
    /// second pass writes the data. Progress is reported via
    /// {@link ProgressListener#onProgress(long, long)} with `rowsTotal = -1`
    /// (total unknown) on each completed chunk.
    ///
    /// @param csvPath    path to the source CSV file
    /// @param vortexPath path to write the output Vortex file
    /// @param options    import configuration
    /// @throws IOException              if reading or writing fails
    /// @throws IllegalArgumentException if the CSV file is empty
    public static void importCsv(Path csvPath, Path vortexPath, ImportOptions options) throws IOException {
        String[] headers = readHeader(csvPath, options);

        DType.Struct schema = options.schema() != null
                ? options.schema()
                : inferSchemaStreaming(csvPath, options, headers);

        try (FileChannel channel = FileChannel.open(
                vortexPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(channel, schema, options.writeOptions())) {
            writeChunksStreaming(csvPath, options, schema, writer);
        }
    }

    private static String[] readHeader(Path path, ImportOptions options) throws IOException {
        try (CsvReader<CsvRecord> reader = csvReader(path, options)) {
            CsvRecord first = reader.stream().findFirst().orElse(null);
            if (first == null) {
                throw new IllegalArgumentException("CSV file is empty");
            }
            String[] fields = first.getFields().toArray(String[]::new);
            return options.hasHeader() ? fields : generateHeaders(fields.length);
        }
    }

    private static DType.Struct inferSchemaStreaming(Path path, ImportOptions options, String[] headers)
            throws IOException {
        int colCount = headers.length;
        boolean[] canBeLong = new boolean[colCount];
        boolean[] canBeDouble = new boolean[colCount];
        boolean[] canBeBool = new boolean[colCount];
        Arrays.fill(canBeLong, true);
        Arrays.fill(canBeDouble, true);
        Arrays.fill(canBeBool, true);

        try (CsvReader<CsvRecord> reader = csvReader(path, options)) {
            boolean skipFirst = options.hasHeader();
            for (CsvRecord record : reader) {
                if (skipFirst) {
                    skipFirst = false;
                    continue;
                }
                String[] fields = record.getFields().toArray(String[]::new);
                for (int c = 0; c < colCount; c++) {
                    String val = safeGet(fields, c);
                    if (val.isEmpty()) {
                        continue;
                    }
                    if (canBeLong[c]) {
                        try {
                            Long.parseLong(val);
                        } catch (NumberFormatException e) {
                            canBeLong[c] = false;
                        }
                    }
                    if (canBeDouble[c]) {
                        try {
                            Double.parseDouble(val);
                        } catch (NumberFormatException e) {
                            canBeDouble[c] = false;
                        }
                    }
                    if (canBeBool[c]) {
                        if (!val.equalsIgnoreCase("true") && !val.equalsIgnoreCase("false")) {
                            canBeBool[c] = false;
                        }
                    }
                }
            }
        }

        List<String> names = List.of(headers);
        List<DType> types = new ArrayList<>(colCount);
        for (int c = 0; c < colCount; c++) {
            types.add(resolveType(canBeLong[c], canBeDouble[c], canBeBool[c]));
        }
        return new DType.Struct(names, types, false);
    }

    private static void writeChunksStreaming(Path path, ImportOptions options, DType.Struct schema,
                                             VortexWriter writer) throws IOException {
        int chunkSize = options.chunkSize();
        List<String[]> chunk = new ArrayList<>(chunkSize);
        long totalRows = 0;

        try (CsvReader<CsvRecord> reader = csvReader(path, options)) {
            boolean skipFirst = options.hasHeader();
            for (CsvRecord record : reader) {
                if (skipFirst) {
                    skipFirst = false;
                    continue;
                }
                chunk.add(record.getFields().toArray(String[]::new));
                if (chunk.size() == chunkSize) {
                    writer.writeChunk(buildChunk(schema, chunk));
                    totalRows += chunk.size();
                    reportProgress(options, totalRows);
                    chunk.clear();
                }
            }
        }

        if (!chunk.isEmpty()) {
            writer.writeChunk(buildChunk(schema, chunk));
            totalRows += chunk.size();
            reportProgress(options, totalRows);
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
            return new DType.Primitive(PType.I64, false);
        }
        if (canBeDouble) {
            return new DType.Primitive(PType.F64, false);
        }
        if (canBeBool) {
            return new DType.Bool(false);
        }
        return new DType.Utf8(false);
    }

    static Map<String, Object> buildChunk(DType.Struct schema, List<String[]> rows) {
        int n = rows.size();
        Map<String, Object> chunk = new LinkedHashMap<>();
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
            case DType.Bool ignored -> {
                boolean[] arr = new boolean[n];
                for (int i = 0; i < n; i++) {
                    arr[i] = Boolean.parseBoolean(safeGet(rows.get(i), colIdx));
                }
                yield arr;
            }
            case DType.Utf8 ignored -> {
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
