package io.github.dfa1.vortex.integration;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;
import io.github.dfa1.vortex.csv.CsvExporter;
import io.github.dfa1.vortex.csv.ExportOptions;
import io.github.dfa1.vortex.parquet.ParquetImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Oracle vs SUT correctness test for the parquet importer.
///
/// Oracle: hardwood reads the parquet file row-by-row and emits a CSV using the same
/// formatting rules as `CsvExporter` (`Long.toString` / `Double.toString` / raw string).
///
/// SUT: `ParquetImporter` converts the parquet file to a Vortex file, then
/// `CsvExporter` exports it to CSV.
///
/// A zero-diff between the two CSVs proves the Java parquet importer preserves every
/// value exactly.
class TaxiParquetOracleVsJavaIntegrationTest {

    // NYC Yellow Taxi 2024-01, same fixture/cache path as
    // ParquetImportIntegrationTest.taxiParquet_importedSize_vsOriginal.
    private static final String TAXI_URL =
            "https://d37ci6vzurychx.cloudfront.net/trip-data/yellow_tripdata_2024-01.parquet";
    private static final String TAXI_NAME = "yellow_tripdata_2024-01.parquet";

    @Test
    void parquetImport_csvExport_matchesDirectParquetRead(@TempDir Path tmp) throws Exception {
        // Given
        Path taxiParquet = LocalHttpCache.downloadIfMissingOrSkip(
                tmp, Path.of("/tmp"), URI.create(TAXI_URL), TAXI_NAME);

        Path oracleCsv = tmp.resolve("oracle.csv");
        Path sutVortex = tmp.resolve("sut.vortex");
        Path sutCsv = tmp.resolve("sut.csv");

        // When — oracle: hardwood → CSV
        writeOracleCsv(taxiParquet, oracleCsv);

        // When — SUT: parquet → vortex → CSV
        ParquetImporter.importParquet(taxiParquet, sutVortex);
        try (BufferedWriter writer = Files.newBufferedWriter(sutCsv)) {
            CsvExporter.exportCsv(sutVortex, writer, ExportOptions.defaults());
        }

        // Then — zero diff
        List<String> oracleLines = Files.readAllLines(oracleCsv);
        List<String> sutLines = Files.readAllLines(sutCsv);

        assertThat(sutLines).hasSameSizeAs(oracleLines);
        for (int i = 0; i < oracleLines.size(); i++) {
            assertThat(sutLines.get(i))
                    .as("line %d", i + 1)
                    .isEqualTo(oracleLines.get(i));
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static void writeOracleCsv(Path parquet, Path csv) throws IOException {
        try (ParquetFileReader pfr = ParquetFileReader.open(InputFile.of(parquet));
             RowReader rows = pfr.rowReader();
             BufferedWriter out = Files.newBufferedWriter(csv)) {

            FileSchema schema = pfr.getFileSchema();
            List<ColumnSchema> cols = schema.getColumns();

            // header
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < cols.size(); c++) {
                if (c > 0) {
                    sb.append(',');
                }
                sb.append(cols.get(c).name());
            }
            out.write(sb.toString());
            out.newLine();

            // rows — same formatting rules as CsvExporter.cellValue()
            while (rows.hasNext()) {
                rows.next();
                sb.setLength(0);
                for (int c = 0; c < cols.size(); c++) {
                    if (c > 0) {
                        sb.append(',');
                    }
                    sb.append(formatCell(cols.get(c), rows));
                }
                out.write(sb.toString());
                out.newLine();
            }
        }
    }

    /// Formats a parquet cell using the same rules as `CsvExporter.cellValue`.
    ///
    /// Null values map to the same defaults as `ParquetImporter.fillRow`:
    /// 0 for numeric types, `false` for booleans, and `""` for strings.
    /// `CsvExporter` then serializes these defaults as their string equivalents.
    ///
    /// @param col    the column schema
    /// @param reader the row reader positioned at the current row
    /// @return the formatted cell string
    private static String formatCell(ColumnSchema col, RowReader reader) {
        String name = col.name();
        boolean isNull = col.repetitionType() == RepetitionType.OPTIONAL && reader.isNull(name);
        return switch (col.type()) {
            case INT32 -> isNull ? "0" : Integer.toString(reader.getInt(name));
            case INT64 -> isNull ? "0" : Long.toString(reader.getLong(name));
            case FLOAT -> isNull ? "0.0" : Float.toString(reader.getFloat(name));
            case DOUBLE -> isNull ? "0.0" : Double.toString(reader.getDouble(name));
            case BOOLEAN -> isNull ? "false" : Boolean.toString(reader.getBoolean(name));
            case BYTE_ARRAY -> isNull ? "" : reader.getString(name);
            default -> throw new UnsupportedOperationException(
                    "unsupported parquet type: " + col.type() + " (column: " + name + ")");
        };
    }
}
