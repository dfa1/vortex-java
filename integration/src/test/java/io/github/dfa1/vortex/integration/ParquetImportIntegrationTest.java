package io.github.dfa1.vortex.integration;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.parquet.ParquetImporter;
import io.github.dfa1.vortex.scan.Chunk;
import io.github.dfa1.vortex.scan.ScanIterator;
import io.github.dfa1.vortex.scan.ScanOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Round-trip: Parquet (via Hardwood) → Vortex (via ParquetImporter) → VortexReader.
///
/// Fixture: delta_encoding_optional_column.parquet from apache/parquet-testing.
/// TPC-DS customer table, 100 rows, INT64 + STRING columns, all nullable.
class ParquetImportIntegrationTest {

    private static final String FIXTURE_URL =
            "https://raw.githubusercontent.com/apache/parquet-testing/master/data/"
                    + "delta_encoding_optional_column.parquet";

    private static final int EXPECTED_ROWS = 100;

    private static long countRows(VortexReader reader) {
        var total = new java.util.concurrent.atomic.AtomicLong();
        try (ScanIterator iter = reader.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> total.addAndGet(c.rowCount()));
        }
        return total.get();
    }

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
        Path cached = Path.of("/tmp/parquet-fixtures", name);
        if (Files.exists(cached)) {
            return cached;
        }
        Path dest = tmp.resolve(name);
        try (var in = URI.create(FIXTURE_URL).toURL().openStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void assumeNetworkAvailable() {
        try {
            URI.create("https://raw.githubusercontent.com").toURL().openStream().close();
        } catch (Exception e) {
            assumeTrue(false, "no network");
        }
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
            assertThat(schema.fieldNames()).containsExactlyElementsOf(parquetColumns);

            long vortexRows = countRows(reader);
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
                VarBinArray col = first.column("c_first_name");
                assertThat(col.getString(0)).isEqualTo("Jeannette");
                assertThat(col.getString(1)).isEqualTo("Austin");
                assertThat(col.getString(2)).isEqualTo("David");
            }
        }
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
            vortexRows = countRows(reader);
        }
        assertThat(vortexRows).isEqualTo(parquetRows);
    }
}
