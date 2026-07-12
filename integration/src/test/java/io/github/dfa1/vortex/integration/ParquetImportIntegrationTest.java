package io.github.dfa1.vortex.integration;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.parquet.ParquetImporter;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Round-trip: Parquet (via Hardwood) → Vortex (via ParquetImporter) → VortexReader.
///
/// Fixture: delta_encoding_optional_column.parquet from apache/parquet-testing.
/// TPC-DS customer table, 100 rows, INT64 + STRING columns, all nullable.
class ParquetImportIntegrationTest {

    private static final String FIXTURE_URL =
            "https://raw.githubusercontent.com/apache/parquet-testing/master/data/"
                    + "delta_encoding_optional_column.parquet";

    private static final int EXPECTED_ROWS = 100;

    private static long countParquetRows(Path path) throws Exception {
        long total = 0;
        try (ParquetFileReader parquet = ParquetFileReader.open(InputFile.of(path));
             RowReader rowReader = parquet.rowReader()) {
            while (rowReader.hasNext()) {
                rowReader.next();
                total++;
            }
        }
        return total;
    }

    private static List<String> parquetColumnNames(Path path) throws Exception {
        try (ParquetFileReader parquet = ParquetFileReader.open(InputFile.of(path))) {
            List<String> names = new ArrayList<>();
            parquet.getFileSchema().getColumns()
                    .forEach(col -> names.add(col.name()));
            return names;
        }
    }

    private static Path download(Path tmp, String name) throws Exception {
        return LocalHttpCache.downloadIfMissing(tmp,
                Path.of("/tmp/parquet-fixtures"), URI.create(FIXTURE_URL), name);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void assumeNetworkAvailable() {
        LocalHttpCache.assumeNetworkAvailable(URI.create("https://raw.githubusercontent.com"));
    }

    @Test
    void rowCountAndColumnNamesMatch(@TempDir Path tmp) throws Exception {
        // Given
        assumeNetworkAvailable();
        Path parquetFile = download(tmp, "delta_encoding_optional_column.parquet");
        Path vortexFile = tmp.resolve("out.vortex");

        // When
        ParquetImporter.importParquet(parquetFile, vortexFile);

        // Then
        List<String> parquetColumns = parquetColumnNames(parquetFile);
        try (VortexReader reader = VortexReader.open(vortexFile)) {
            assertThat(reader.dtype()).isInstanceOf(DType.Struct.class);
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldNames().stream().map(io.github.dfa1.vortex.core.model.ColumnName::value).toList()).containsExactlyElementsOf(parquetColumns);

            long vortexRows = reader.layout().rowCount();
            assertThat(vortexRows).isEqualTo(EXPECTED_ROWS);
        }
    }

    @Test
    void longColumnValuesMatch(@TempDir Path tmp) throws Exception {
        // Given
        assumeNetworkAvailable();
        Path parquetFile = download(tmp, "delta_encoding_optional_column.parquet");
        Path vortexFile = tmp.resolve("out.vortex");

        // When
        ParquetImporter.importParquet(parquetFile, vortexFile);

        // Then — c_customer_sk (INT64, nullable): first 3 values are 100, 99, 98
        try (VortexReader reader = VortexReader.open(vortexFile);
             ScanIterator iter = reader.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk first = iter.next()) {
                LongArray col = first.column("c_customer_sk");
                assertThat(col.getLong(0)).isEqualTo(100L);
                assertThat(col.getLong(1)).isEqualTo(99L);
                assertThat(col.getLong(2)).isEqualTo(98L);
            }
        }
    }

    @Test
    void stringColumnValuesMatch(@TempDir Path tmp) throws Exception {
        // Given
        assumeNetworkAvailable();
        Path parquetFile = download(tmp, "delta_encoding_optional_column.parquet");
        Path vortexFile = tmp.resolve("out.vortex");

        // When
        ParquetImporter.importParquet(parquetFile, vortexFile);

        // Then — c_first_name (STRING, nullable): first 3 values
        try (VortexReader reader = VortexReader.open(vortexFile);
             ScanIterator iter = reader.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk first = iter.next()) {
                // Nullable Utf8 decodes as MaskedArray (validity + VarBin values); rows 0-2 are non-null.
                MaskedArray col = first.column("c_first_name");
                VarBinArray values = (VarBinArray) col.inner();
                assertThat(values.getString(0)).isEqualTo("Jeannette");
                assertThat(values.getString(1)).isEqualTo("Austin");
                assertThat(values.getString(2)).isEqualTo("David");
            }
        }
    }

    @Test
    void taxiParquet_importedSize_vsOriginal(@TempDir Path tmp) throws Exception {
        // Given — NYC Yellow Taxi 2024-01 (~3M rows, 19 cols, mix of I64 / F64 / I32 / Utf8).
        // Same fixture/cache path as TaxiParquetOracleVsJavaIntegrationTest. CloudFront
        // rate-limits/blocks some egress IPs (notably GitHub Actions runners → 403), so any
        // download failure skips rather than fails.
        Path src = LocalHttpCache.downloadIfMissingOrSkip(tmp, Path.of("/tmp"),
                URI.create("https://d37ci6vzurychx.cloudfront.net/trip-data/yellow_tripdata_2024-01.parquet"),
                "yellow_tripdata_2024-01.parquet");
        Path vortex = tmp.resolve("taxi.vortex");

        // When
        ParquetImporter.importParquet(src, vortex);

        // Then
        long parquetSize = Files.size(src);
        long vortexSize = Files.size(vortex);
        System.out.printf(
                "[TaxiSizeComparison] Parquet=%,d bytes (%.1f MB)  Vortex=%,d bytes (%.1f MB)  Vortex/Parquet=%.2fx%n",
                parquetSize, parquetSize / 1_048_576.0,
                vortexSize, vortexSize / 1_048_576.0,
                (double) vortexSize / parquetSize);
        assertThat(vortexSize).isGreaterThan(0);
    }

    @Test
    void parquetAndVortexRowCountsAreEqual(@TempDir Path tmp) throws Exception {
        // Given
        assumeNetworkAvailable();
        Path parquetFile = download(tmp, "delta_encoding_optional_column.parquet");
        Path vortexFile = tmp.resolve("out.vortex");

        // When
        ParquetImporter.importParquet(parquetFile, vortexFile);

        // Then — same row count when reading both with their native reader
        long parquetRows = countParquetRows(parquetFile);
        long vortexRows;
        try (VortexReader reader = VortexReader.open(vortexFile)) {
            vortexRows = reader.layout().rowCount();
        }
        assertThat(vortexRows).isEqualTo(parquetRows);
    }
}
