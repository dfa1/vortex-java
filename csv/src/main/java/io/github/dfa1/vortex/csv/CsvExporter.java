package io.github.dfa1.vortex.csv;

import de.siegmar.fastcsv.writer.CsvWriter;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.Chunk;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;

/// Reads a Vortex file and writes rows to a CSV destination.
///
/// The header row is derived from [DType.Struct] field names.
/// Only struct root dtype is supported; throws [VortexException] otherwise.
public final class CsvExporter {

    private CsvExporter() {
    }

    public static void exportCsv(Path vortexPath, Path csvPath) throws IOException {
        exportCsv(vortexPath, csvPath, ExportOptions.defaults());
    }

    public static void exportCsv(Path vortexPath, Path csvPath, ExportOptions options) throws IOException {
        try (VortexReader reader = VortexReader.open(vortexPath);
             CsvWriter csvWriter = CsvWriter.builder()
                                           .fieldSeparator(options.delimiter())
                                           .build(csvPath)) {
            export(reader, csvWriter, options, ScanOptions.all(), RowPredicate.all());
        }
    }

    /// Export to a caller-owned [Writer]; the writer is flushed but not closed.
    public static void exportCsv(Path vortexPath, Writer out, ExportOptions options) throws IOException {
        exportCsvFiltered(vortexPath, out, options, ScanOptions.all(), RowPredicate.all());
    }

    /// Like [#exportCsv(Path, Writer, ExportOptions)] but with zone-map chunk pruning
    /// ([ScanOptions#rowFilter()]) and a row-level predicate applied after decoding.
    public static void exportCsvFiltered(Path vortexPath, Writer out, ExportOptions options,
            ScanOptions scanOptions, RowPredicate predicate) throws IOException {
        Writer shielded = new FilterWriter(out) {
            @Override
            public void close() {
                // do not close the caller-owned writer
            }
        };
        try (VortexReader reader = VortexReader.open(vortexPath);
             CsvWriter csvWriter = CsvWriter.builder()
                                           .fieldSeparator(options.delimiter())
                                           .build(shielded)) {
            export(reader, csvWriter, options, scanOptions, predicate);
        }
    }

    private static void export(VortexReader reader, CsvWriter csvWriter, ExportOptions options,
            ScanOptions scanOptions, RowPredicate predicate) {
        if (!(reader.dtype() instanceof DType.Struct schema)) {
            throw new VortexException("only struct root dtype supported for CSV export");
        }
        List<String> colNames = options.hasProjection()
                ? options.columns()
                : schema.fieldNames().stream().map(ColumnName::value).toList();
        int colCount = colNames.size();

        if (options.writeHeader()) {
            csvWriter.writeRecord(colNames);
        }

        ProgressListener progress = options.progressListener();
        long rowsTotal = progress != null ? reader.layout().rowCount() : 0L;
        long[] state = {0L, 1_000L}; // state[0]=rowsDone, state[1]=nextNotify
        String[] row = new String[colCount];
        try (ScanIterator iter = reader.scan(scanOptions)) {
            while (iter.hasNext()) {
                try (Chunk chunk = iter.next()) {
                    writeChunk(chunk, colNames, colCount, row, csvWriter, predicate, progress, rowsTotal, state);
                }
            }
        }
        if (progress != null) {
            progress.onProgress(state[0], rowsTotal);
        }
    }

    private static void writeChunk(Chunk chunk, List<String> colNames, int colCount,
            String[] row, CsvWriter csvWriter, RowPredicate predicate,
            ProgressListener progress, long rowsTotal, long[] state) {
        Array[] arrays = new Array[colCount];
        for (int c = 0; c < colCount; c++) {
            arrays[c] = chunk.column(colNames.get(c));
        }
        // Some files chunk per column independently. ChunkSpec.rowCount
        // tracks the first column's flat, so columns whose flats were
        // shorter would OOB on the tail rows. Clamp to the shortest
        // column length so the writer/reader mismatch only loses tail
        // rows instead of crashing mid-export.
        long rowCount = chunk.rowCount();
        for (Array a : arrays) {
            if (a != null && a.length() < rowCount) {
                rowCount = a.length();
            }
        }
        long rowsDone = state[0];
        long nextNotify = state[1];
        for (long r = 0; r < rowCount; r++) {
            if (!predicate.test(chunk, r)) {
                continue;
            }
            for (int c = 0; c < colCount; c++) {
                row[c] = cellValue(arrays[c], r);
            }
            csvWriter.writeRecord(row);
            rowsDone++;
            if (progress != null && rowsDone >= nextNotify) {
                progress.onProgress(rowsDone, rowsTotal);
                nextNotify = rowsDone + 1_000L;
            }
        }
        state[0] = rowsDone;
        state[1] = nextNotify;
    }

    /// Renders one cell for the row `rowIdx` of column array `arr`.
    ///
    /// Leaf columns follow the JDK canonical rendering per type (signed/unsigned integer decimal,
    /// `Double.toString`, `Boolean.toString`, the raw utf8 string); a null row (a [MaskedArray]
    /// invalid row or a [NullArray]) renders as an empty CSV field.
    ///
    /// A nested struct column ([StructArray], possibly wrapped in a [MaskedArray] when nullable)
    /// renders as a single JSON object cell `{"field":value,...}` with fields in the struct dtype's
    /// declared order. Field values reuse the same leaf rendering rules — numbers and booleans
    /// unquoted, strings JSON-escaped and double-quoted, nested structs recursed — and a null field
    /// (or null nested row) becomes a JSON `null`. Only the JSON layer is escaped here; the CSV
    /// writer independently quotes any cell containing the delimiter or a quote character.
    ///
    /// @param arr    the column array to read from
    /// @param rowIdx the zero-based row index within `arr`
    /// @return the rendered cell text
    private static String cellValue(Array arr, long rowIdx) {
        return switch (arr) {
            // Long/IntArray have no dtype-aware getter, so gate the unsigned rendering on the
            // ptype; U64/U32 high-half values would otherwise print as negative.
            case LongArray la -> isUnsigned(la)
                    ? Long.toUnsignedString(la.getLong(rowIdx))
                    : Long.toString(la.getLong(rowIdx));
            case IntArray ia -> isUnsigned(ia)
                    ? Integer.toUnsignedString(ia.getInt(rowIdx))
                    : Integer.toString(ia.getInt(rowIdx));
            // Byte/ShortArray.getInt already zero-extends U8/U16 per their dtype, so widening to
            // int and printing signed decimal yields the correct unsigned value.
            case ShortArray sa -> Integer.toString(sa.getInt(rowIdx));
            case ByteArray ba -> Integer.toString(ba.getInt(rowIdx));
            case DoubleArray da -> Double.toString(da.getDouble(rowIdx));
            case FloatArray fa -> Float.toString(fa.getFloat(rowIdx));
            case BoolArray ba -> Boolean.toString(ba.getBoolean(rowIdx));
            case VarBinArray va -> va.getString(rowIdx);
            // Nullable columns decode as MaskedArray: null rows export as an empty field, valid
            // rows defer to the inner values array.
            case MaskedArray ma -> ma.isValid(rowIdx) ? cellValue(ma.inner(), rowIdx) : "";
            // All-null columns (DType.Null) hold only a row count: every cell is an empty
            // field, same rule as a MaskedArray null row.
            case NullArray ignored -> "";
            // Nested struct column: render the whole row as a JSON object cell.
            case StructArray sa -> jsonObject(sa, rowIdx);
            default -> throw new VortexException(
                    "unsupported array type for CSV export: " + arr.getClass().getSimpleName());
        };
    }

    /// Renders one struct row as a JSON object `{"field":value,...}` with fields in the struct
    /// dtype's declared order.
    private static String jsonObject(StructArray struct, long rowIdx) {
        DType.Struct dtype = (DType.Struct) struct.dtype();
        List<ColumnName> names = dtype.fieldNames();
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            jsonString(sb, names.get(i).value());
            sb.append(':');
            sb.append(jsonValue(struct.field(i), rowIdx));
        }
        sb.append('}');
        return sb.toString();
    }

    /// Renders one struct field value as JSON text: a nested object for structs, a quoted escaped
    /// string for utf8, JSON `null` for a null row/field, and the bare leaf rendering (unquoted
    /// number/boolean) for everything else. Unlike [#cellValue(Array, long)], a null here is the
    /// JSON token `null` rather than an empty field, because inside a JSON object the empty-field
    /// convention of bare CSV would be ambiguous.
    private static String jsonValue(Array arr, long rowIdx) {
        return switch (arr) {
            case StructArray sa -> jsonObject(sa, rowIdx);
            case MaskedArray ma -> ma.isValid(rowIdx) ? jsonValue(ma.inner(), rowIdx) : "null";
            case NullArray ignored -> "null";
            case VarBinArray va -> {
                StringBuilder sb = new StringBuilder();
                jsonString(sb, va.getString(rowIdx));
                yield sb.toString();
            }
            // Numeric and boolean leaves render identically to a top-level cell, unquoted.
            default -> cellValue(arr, rowIdx);
        };
    }

    /// Appends `value` to `sb` as a JSON string literal: wrapped in double quotes with `"`,
    /// backslash, the standard short escapes, and any other control character below `U+0020`
    /// escaped as `\\uXXXX`.
    private static void jsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /// Whether the array's dtype is an unsigned integer, so high-half values must render as
    /// unsigned decimal rather than the two's-complement negative that the raw signed getter
    /// returns. Callers only pass leaf value arrays: the [MaskedArray] arm of `cellValue`
    /// recurses into the inner array before any integer arm is reached.
    private static boolean isUnsigned(Array arr) {
        return arr.dtype() instanceof DType.Primitive p && p.ptype().isUnsigned();
    }
}
