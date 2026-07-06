package io.github.dfa1.vortex.integration;

import de.siegmar.fastcsv.writer.CsvWriter;
import dev.hardwood.InputFile;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnSchema;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.csv.CsvExporter;
import io.github.dfa1.vortex.csv.ExportOptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Real-world cross-implementation conformance over the Raincloud corpus
/// ([issue #205](https://github.com/dfa1/vortex-java/issues/205)).
///
/// Each corpus dataset is a `.vortex` file written by the Vortex Python bindings with
/// its `.parquet` sibling built from the same Arrow table. The parquet file is the
/// oracle: hardwood reads it row-by-row and formats cells with the exact rules of
/// `CsvExporter.cellValue` (null → empty field, `Double.toString`, …), writing through
/// the same fastcsv writer so quoting is identical. A zero-diff against vortex-java's
/// CSV export proves every value survived the Python-write / Java-read boundary.
///
/// Skipped (visibly, via assumption) when the corpus is not hydrated — run
/// `scripts/hydrate-raincloud-corpus.sh` first, or point `RAINCLOUD_CORPUS_MANIFEST`
/// at a manifest TSV (`slug<TAB>vortex-path<TAB>parquet-path` per line).
///
/// Expected per-slug status lives in `src/test/resources/raincloud/expected-status.csv`.
/// Known gaps assert that the failure still occurs, so fixing the reader forces the
/// matrix entry to flip to `ok` in the same change.
class RaincloudConformanceIntegrationTest {

    private static final Path DEFAULT_MANIFEST =
            Path.of(System.getProperty("user.home"), ".cache", "raincloud", "corpus-manifest.tsv");

    @Test
    void corpusIsHydrated() {
        // Given / When / Then — visible skip marker when the corpus is absent; the
        // factory below yields zero tests in that case, which would otherwise pass silently
        assumeTrue(Files.exists(manifestPath()),
                "raincloud corpus not hydrated — run scripts/hydrate-raincloud-corpus.sh");
    }

    @TestFactory
    Stream<DynamicTest> conformancePerSlug() throws IOException {
        Path manifest = manifestPath();
        if (!Files.exists(manifest)) {
            return Stream.empty();
        }
        Map<String, String> expected = readExpectedStatus();
        return Files.readAllLines(manifest).stream()
                .filter(line -> !line.isBlank())
                .map(line -> line.split("\t"))
                .map(parts -> DynamicTest.dynamicTest(parts[0], () -> {
                    String slug = parts[0];
                    Path vortex = Path.of(parts[1]);
                    Path parquet = Path.of(parts[2]);
                    // slugs missing from the matrix are treated as untriaged, not failing
                    String status = expected.getOrDefault(slug, "untriaged");
                    switch (status) {
                        case "ok" -> assertMatchesParquetOracle(vortex, parquet);
                        case "untriaged" -> reportUntriaged(vortex, parquet);
                        default -> assertStillFails(vortex, parquet, status);
                    }
                }));
    }

    private static void assertMatchesParquetOracle(Path vortex, Path parquet) throws IOException {
        // Given
        List<String> oracleLines = oracleLines(parquet);

        // When
        List<String> result = exportVortex(vortex);

        // Then
        assertLinesMatch(result, oracleLines);
    }

    /// Runs the full conformance check but reports the outcome as an aborted test
    /// either way: only triaged matrix entries may count as green or red. The abort
    /// message says which way to flip the entry.
    private static void reportUntriaged(Path vortex, Path parquet) {
        try {
            assertMatchesParquetOracle(vortex, parquet);
        } catch (TestAbortedException e) {
            throw e; // oracle limitation, not a conformance outcome
        } catch (AssertionError | Exception e) {
            throw new TestAbortedException(
                    "untriaged slug fails — classify as gap:<issue> in expected-status.csv: " + e);
        }
        throw new TestAbortedException("untriaged slug passes — flip its matrix entry to ok");
    }

    private static void assertStillFails(Path vortex, Path parquet, String status) {
        // Given / When — a decode gap still reproduces when the export itself throws.
        // Only VortexException (the contractual untrusted-input failure) and, narrowly,
        // IndexOutOfBoundsException count: the latter is itself a bounds-guard bug (#215
        // throws it today) and this arm dies with that fix — a blanket RuntimeException
        // catch would green unrelated regressions (NPEs, ...). No oracle needed here
        // (the oracle may not even read this parquet, e.g. nested columns).
        List<String> result;
        try {
            result = exportVortex(vortex);
        } catch (VortexException | IndexOutOfBoundsException e) {
            return;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Then — the export now succeeds, so the gap must still be a silent-corruption
        // one (e.g. #208): values must still mismatch the oracle. A clean pass means the
        // gap was fixed: flip the expected-status.csv entry to ok in the same change
        List<String> oracleLines = oracleLines(parquet);
        assertThatThrownBy(() -> assertLinesMatch(result, oracleLines))
                .as("known gap %s no longer reproduces — flip its matrix entry to ok", status)
                .isInstanceOf(AssertionError.class);
    }

    private static void assertLinesMatch(List<String> result, List<String> oracleLines) {
        assertThat(result).hasSameSizeAs(oracleLines);
        for (int i = 0; i < oracleLines.size(); i++) {
            assertThat(result.get(i)).as("line %d", i + 1).isEqualTo(oracleLines.get(i));
        }
    }

    private static List<String> exportVortex(Path vortex) throws IOException {
        StringWriter out = new StringWriter();
        CsvExporter.exportCsv(vortex, out, ExportOptions.defaults());
        return out.toString().lines().toList();
    }

    /// Reads the parquet sibling through hardwood; an oracle-side failure (nested
    /// columns, unsupported physical type) aborts the slug rather than failing it —
    /// it says nothing about vortex-java. Deliberate asymmetry: an `ok` slug whose
    /// parquet the oracle cannot read stops being verified (visibly, as skipped) —
    /// widen the oracle rather than let unverifiable entries fail the build.
    private static List<String> oracleLines(Path parquet) {
        StringWriter out = new StringWriter();
        try {
            writeOracleCsv(parquet, out);
        } catch (TestAbortedException e) {
            throw e;
        } catch (Exception e) {
            throw new TestAbortedException("oracle cannot read the parquet sibling: " + e);
        }
        return out.toString().lines().toList();
    }

    /// Oracle: hardwood reads the parquet sibling and emits CSV through the same
    /// fastcsv writer configuration as `CsvExporter`, using its exact cell rules.
    private static void writeOracleCsv(Path parquet, StringWriter out) throws IOException {
        try (ParquetFileReader pfr = ParquetFileReader.open(InputFile.of(parquet));
             RowReader rows = pfr.rowReader();
             CsvWriter csv = CsvWriter.builder().fieldSeparator(',').build(out)) {

            List<ColumnSchema> cols = pfr.getFileSchema().getColumns();
            csv.writeRecord(cols.stream().map(ColumnSchema::name).toList());

            String[] row = new String[cols.size()];
            while (rows.hasNext()) {
                rows.next();
                for (int c = 0; c < cols.size(); c++) {
                    row[c] = oracleCell(cols.get(c), rows);
                }
                csv.writeRecord(row);
            }
        }
    }

    /// Formats a parquet cell with the exact rules of `CsvExporter.cellValue`:
    /// null rows export as an empty field, valid rows use the JDK canonical
    /// `toString` of the value.
    ///
    /// @param col  the column schema
    /// @param rows the row reader positioned at the current row
    /// @return the formatted cell string
    private static String oracleCell(ColumnSchema col, RowReader rows) {
        String name = col.name();
        if (col.repetitionType() == RepetitionType.OPTIONAL && rows.isNull(name)) {
            return "";
        }
        return switch (col.type()) {
            case INT32 -> Integer.toString(rows.getInt(name));
            case INT64 -> Long.toString(rows.getLong(name));
            case FLOAT -> Float.toString(rows.getFloat(name));
            case DOUBLE -> Double.toString(rows.getDouble(name));
            case BOOLEAN -> Boolean.toString(rows.getBoolean(name));
            case BYTE_ARRAY -> rows.getString(name);
            // aborts (not fails) the slug: the oracle can't format this physical type
            // yet, which is an oracle limitation rather than a vortex-java gap
            default -> throw new TestAbortedException(
                    "oracle cannot format parquet type " + col.type() + " (column: " + name + ")");
        };
    }

    private static Path manifestPath() {
        String env = System.getenv("RAINCLOUD_CORPUS_MANIFEST");
        return env != null ? Path.of(env) : DEFAULT_MANIFEST;
    }

    private static Map<String, String> readExpectedStatus() throws IOException {
        try (var in = RaincloudConformanceIntegrationTest.class
                .getResourceAsStream("/raincloud/expected-status.csv")) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("expected-status.csv not on test classpath"));
            }
            Map<String, String> statuses = new HashMap<>();
            for (String line : new String(in.readAllBytes()).lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(",", 2);
                statuses.put(parts[0].trim(), parts[1].trim());
            }
            return statuses;
        }
    }
}
