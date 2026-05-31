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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Parses a CSV file and writes a Vortex file.
///
/// Column types are inferred in priority order: long → double → boolean → utf8.
/// Provide a schema via [ImportOptions#withSchema] to skip inference.
/// Empty cell values are treated as 0 / false / "" for typed columns.
public final class CsvImporter {

    private CsvImporter() {
    }

    public static void importCsv(Path csvPath, Path vortexPath) throws IOException {
        importCsv(csvPath, vortexPath, ImportOptions.defaults());
    }

    public static void importCsv(Path csvPath, Path vortexPath, ImportOptions options) throws IOException {
        List<String[]> rows = readAllRows(csvPath, options);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV file is empty");
        }

        String[] headers;
        int dataStart;
        if (options.hasHeader()) {
            headers = rows.getFirst();
            dataStart = 1;
        } else {
            headers = generateHeaders(rows.getFirst().length);
            dataStart = 0;
        }

        List<String[]> dataRows = rows.subList(dataStart, rows.size());
        int colCount = headers.length;

        DType.Struct schema;
        if (options.schema() != null) {
            schema = options.schema();
        } else {
            schema = inferSchema(headers, dataRows, colCount);
        }

        try (FileChannel channel = FileChannel.open(
                vortexPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(channel, schema, options.writeOptions())) {
            int chunkSize = options.chunkSize();
            int total = dataRows.size();
            for (int start = 0; start < total; start += chunkSize) {
                int end = Math.min(start + chunkSize, total);
                writer.writeChunk(buildChunk(schema, dataRows.subList(start, end)));
                if (options.progressListener() != null) {
                    options.progressListener().onProgress(end, total);
                }
            }
        }
    }

    private static List<String[]> readAllRows(Path path, ImportOptions options) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (CsvReader<CsvRecord> reader = CsvReader.builder()
                .fieldSeparator(options.delimiter())
                .ofCsvRecord(path)) {
            for (CsvRecord record : reader) {
                rows.add(record.getFields().toArray(String[]::new));
            }
        }
        return rows;
    }

    private static String[] generateHeaders(int colCount) {
        String[] headers = new String[colCount];
        for (int i = 0; i < colCount; i++) {
            headers[i] = "col" + i;
        }
        return headers;
    }

    private static DType.Struct inferSchema(String[] headers, List<String[]> rows, int colCount) {
        List<String> names = List.of(headers);
        List<DType> types = new ArrayList<>(colCount);
        for (int c = 0; c < colCount; c++) {
            types.add(inferColumnType(rows, c));
        }
        return new DType.Struct(names, types, false);
    }

    /// Infers the narrowest type for a column using a single pass.
    ///
    /// Priority: long → double → bool → utf8. Each flag starts true and can only
    /// transition to false. Empty cells are skipped (compatible with any type).
    /// An all-empty column is inferred as long (all flags remain true).
    ///
    /// Integer values always infer as i64 (not i32/i16): CSV has no type annotations,
    /// so the widest safe integer is chosen. Use [ImportOptions#withSchema] to force i32/i16.
    /// Floating-point values always infer as f64 (not f32) for the same reason.
    private static DType inferColumnType(List<String[]> rows, int colIdx) {
        boolean canBeLong = true;
        boolean canBeDouble = true;
        boolean canBeBool = true;

        for (String[] row : rows) {
            String val = safeGet(row, colIdx);
            if (val.isEmpty()) {
                continue;
            }
            if (canBeLong) {
                try {
                    Long.parseLong(val);
                } catch (NumberFormatException e) {
                    canBeLong = false;
                }
            }
            if (canBeDouble) {
                try {
                    Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    canBeDouble = false;
                }
            }
            if (canBeBool) {
                if (!val.equalsIgnoreCase("true") && !val.equalsIgnoreCase("false")) {
                    canBeBool = false;
                }
            }
        }

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

    private static Map<String, Object> buildChunk(DType.Struct schema, List<String[]> rows) {
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
