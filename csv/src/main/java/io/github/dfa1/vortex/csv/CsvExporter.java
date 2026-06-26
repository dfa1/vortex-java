package io.github.dfa1.vortex.csv;

import de.siegmar.fastcsv.writer.CsvWriter;
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
        List<String> colNames = options.hasProjection() ? options.columns() : schema.fieldNames();
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

    private static String cellValue(Array arr, long rowIdx) {
        return switch (arr) {
            case LongArray la -> Long.toString(la.getLong(rowIdx));
            case IntArray ia -> Integer.toString(ia.getInt(rowIdx));
            case ShortArray sa -> Short.toString(sa.getShort(rowIdx));
            case ByteArray ba -> Byte.toString(ba.getByte(rowIdx));
            case DoubleArray da -> Double.toString(da.getDouble(rowIdx));
            case FloatArray fa -> Float.toString(fa.getFloat(rowIdx));
            case BoolArray ba -> Boolean.toString(ba.getBoolean(rowIdx));
            case VarBinArray va -> va.getString(rowIdx);
            // Nullable columns decode as MaskedArray: null rows export as an empty field, valid
            // rows defer to the inner values array.
            case MaskedArray ma -> ma.isValid(rowIdx) ? cellValue(ma.inner(), rowIdx) : "";
            default -> throw new VortexException(
                    "unsupported array type for CSV export: " + arr.getClass().getSimpleName());
        };
    }
}
