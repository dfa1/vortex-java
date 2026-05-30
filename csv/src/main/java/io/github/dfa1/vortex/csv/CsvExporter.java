package io.github.dfa1.vortex.csv;

import de.siegmar.fastcsv.writer.CsvWriter;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanIterator;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.scan.ScanResult;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
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
            export(reader, csvWriter, options);
        }
    }

    /// Export to a caller-owned [Writer]; the writer is flushed but not closed.
    public static void exportCsv(Path vortexPath, Writer out, ExportOptions options) throws IOException {
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
            export(reader, csvWriter, options);
        }
    }

    private static void export(VortexReader reader, CsvWriter csvWriter, ExportOptions options) throws IOException {
        if (!(reader.dtype() instanceof DType.Struct schema)) {
            throw new VortexException("only struct root dtype supported for CSV export");
        }
        List<String> colNames = schema.fieldNames();
        int colCount = colNames.size();

        if (options.writeHeader()) {
            csvWriter.writeRecord(colNames);
        }

        String[] row = new String[colCount];
        try (ScanIterator iter = reader.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                ScanResult chunk = iter.next();
                Array[] arrays = new Array[colCount];
                for (int c = 0; c < colCount; c++) {
                    arrays[c] = chunk.column(colNames.get(c));
                }
                long rowCount = chunk.rowCount();
                for (long r = 0; r < rowCount; r++) {
                    for (int c = 0; c < colCount; c++) {
                        row[c] = cellValue(arrays[c], r);
                    }
                    csvWriter.writeRecord(row);
                }
            }
        }
    }

    private static String cellValue(Array arr, long rowIdx) {
        return switch (arr) {
            case LongArray la -> Long.toString(la.getLong(rowIdx));
            case IntArray ia -> Integer.toString(ia.getInt(rowIdx));
            case ShortArray sa -> Short.toString(sa.getShort(rowIdx));
            case ByteArray ba -> Byte.toString(ba.getByte(rowIdx));
            case DoubleArray da -> Double.toString(da.getDouble(rowIdx));
            case FloatArray fa -> Float.toString(fa.getFloat(rowIdx));
            case BoolArray ba -> Boolean.toString(ba.getBoolean(rowIdx));
            case VarBinArray va -> new String(va.getBytes(rowIdx), StandardCharsets.UTF_8);
            default -> throw new VortexException(
                    "unsupported array type for CSV export: " + arr.getClass().getSimpleName());
        };
    }
}
