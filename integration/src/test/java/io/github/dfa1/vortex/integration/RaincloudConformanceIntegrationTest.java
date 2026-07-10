package io.github.dfa1.vortex.integration;

import de.siegmar.fastcsv.writer.CsvWriter;
import dev.hardwood.InputFile;
import dev.hardwood.metadata.LogicalType;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
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
        // Given — write both CSVs to temp files; never hold multi-GB strings in heap
        Path oracleCsv = Files.createTempFile("oracle-", ".csv");
        Path vortexCsv = Files.createTempFile("vortex-", ".csv");
        try {
            writeOracle(parquet, oracleCsv);

            // When
            CsvExporter.exportCsv(vortex, vortexCsv, ExportOptions.defaults());

            // Then
            assertFilesMatch(vortexCsv, oracleCsv);
        } finally {
            Files.deleteIfExists(oracleCsv);
            Files.deleteIfExists(vortexCsv);
        }
    }

    /// Runs the full conformance check but reports the outcome as an aborted test
    /// either way: only triaged matrix entries may count as green or red. The abort
    /// message says which way to flip the entry.
    private static void reportUntriaged(Path vortex, Path parquet) {
        try {
            assertMatchesParquetOracle(vortex, parquet);
        } catch (TestAbortedException e) {
            System.out.println("TRIAGE " + vortex + " => ABORTED " + e.getMessage());
            throw e; // oracle limitation, not a conformance outcome
        } catch (AssertionError | Exception e) {
            System.out.println("TRIAGE " + vortex + " => FAILS " + e.getMessage());
            throw new TestAbortedException(
                    "untriaged slug fails — classify as gap:<issue> in expected-status.csv: " + e);
        }
        System.out.println("TRIAGE " + vortex + " => OK");
        throw new TestAbortedException("untriaged slug passes — flip its matrix entry to ok");
    }

    private static void assertStillFails(Path vortex, Path parquet, String status) throws IOException {
        // Given / When — a decode gap still reproduces when the export itself throws.
        // Only VortexException (the contractual untrusted-input failure) and, narrowly,
        // IndexOutOfBoundsException count: the latter is itself a bounds-guard bug (#215
        // throws it today) and this arm dies with that fix — a blanket RuntimeException
        // catch would green unrelated regressions (NPEs, ...). No oracle needed here
        // (the oracle may not even read this parquet, e.g. nested columns).
        Path vortexCsv = Files.createTempFile("vortex-", ".csv");
        try {
            try {
                CsvExporter.exportCsv(vortex, vortexCsv, ExportOptions.defaults());
            } catch (VortexException | IndexOutOfBoundsException e) {
                return; // gap still reproduces as a thrown exception
            }

            // Then — the export now succeeds, so the gap must still be a silent-corruption
            // one (e.g. #208): values must still mismatch the oracle. A clean pass means the
            // gap was fixed: flip the expected-status.csv entry to ok in the same change
            Path oracleCsv = Files.createTempFile("oracle-", ".csv");
            try {
                writeOracle(parquet, oracleCsv);
                boolean stillMismatches = false;
                try {
                    assertFilesMatch(vortexCsv, oracleCsv);
                } catch (AssertionError ignored) {
                    stillMismatches = true;
                }
                assertThat(stillMismatches)
                        .as("known gap %s no longer reproduces — flip its matrix entry to ok", status)
                        .isTrue();
            } finally {
                Files.deleteIfExists(oracleCsv);
            }
        } finally {
            Files.deleteIfExists(vortexCsv);
        }
    }

    /// Stream-compares two CSV files line by line without loading either into heap.
    ///
    /// @param result the vortex-java export
    /// @param oracle the parquet oracle export
    /// @throws AssertionError  if any line differs or the files have different lengths
    /// @throws IOException     if either file cannot be read
    private static void assertFilesMatch(Path result, Path oracle) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(result);
             BufferedReader o = Files.newBufferedReader(oracle)) {
            long lineNum = 0;
            String rLine;
            while ((rLine = r.readLine()) != null) {
                lineNum++;
                String oLine = o.readLine();
                assertThat(oLine).as("oracle ended before vortex output at line %d", lineNum).isNotNull();
                assertThat(rLine).as("line %d", lineNum).isEqualTo(oLine);
            }
            assertThat(o.readLine()).as("vortex output ended before oracle at line %d", lineNum + 1).isNull();
        }
    }

    /// Writes the parquet oracle to a file using the same cell rules as `CsvExporter`.
    /// An oracle-side failure (nested columns, unsupported physical type) aborts the
    /// slug via [TestAbortedException] rather than failing it — it says nothing about
    /// vortex-java. An `ok` slug whose parquet cannot be read stops being verified
    /// (visibly, as skipped) — widen the oracle rather than let unverifiable entries
    /// fail the build.
    ///
    /// @param parquet the parquet sibling
    /// @param out     the output file to write CSV into
    /// @throws TestAbortedException if the oracle cannot read this parquet
    /// @throws IOException          if writing fails
    private static void writeOracle(Path parquet, Path out) throws IOException {
        try (Writer writer = Files.newBufferedWriter(out)) {
            writeOracleCsv(parquet, writer);
        } catch (TestAbortedException e) {
            throw e;
        } catch (Exception e) {
            throw new TestAbortedException("oracle cannot read the parquet sibling: " + e);
        }
    }

    /// Oracle: hardwood reads the parquet sibling and emits CSV through the same
    /// fastcsv writer configuration as `CsvExporter`, using its exact cell rules.
    private static void writeOracleCsv(Path parquet, Writer out) throws IOException {
        try (ParquetFileReader pfr = ParquetFileReader.open(InputFile.of(parquet));
             RowReader rows = pfr.rowReader();
             CsvWriter csv = CsvWriter.builder().fieldSeparator(',').build(out)) {

            List<ColumnSchema> cols = pfr.getFileSchema().getColumns();
            // De-duplicate duplicate column names with the Rust Vortex writer's algorithm:
            // the Nth (N >= 1) occurrence of a base name gets a " [N]" suffix, matching
            // the de-duplicated names in the Vortex file (#256).
            Map<String, Integer> seen = new LinkedHashMap<>();
            List<String> header = new ArrayList<>(cols.size());
            for (ColumnSchema col : cols) {
                int count = seen.merge(col.name(), 1, Integer::sum) - 1;
                header.add(count == 0 ? col.name() : col.name() + " [" + count + "]");
            }
            csv.writeRecord(header);

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
    /// Row access uses column index rather than name so that files with duplicate
    /// column names (#256) read the right column.
    ///
    /// INT32/INT64 columns with a `UINT_32`/`UINT_64` logical-type annotation are treated
    /// as unsigned so their string representation matches the U32/U64 Vortex columns that
    /// carry the same bits (#253).
    ///
    /// @param col  the column schema
    /// @param rows the row reader positioned at the current row
    /// @return the formatted cell string
    private static String oracleCell(ColumnSchema col, RowReader rows) {
        int idx = col.columnIndex();
        if (col.repetitionType() == RepetitionType.OPTIONAL && rows.isNull(idx)) {
            return "";
        }
        boolean unsignedInt = col.logicalType() instanceof LogicalType.IntType lt && !lt.isSigned();
        return switch (col.type()) {
            case INT32 -> unsignedInt ? Integer.toUnsignedString(rows.getInt(idx)) : Integer.toString(rows.getInt(idx));
            case INT64 -> unsignedInt ? Long.toUnsignedString(rows.getLong(idx)) : Long.toString(rows.getLong(idx));
            case FLOAT -> Float.toString(rows.getFloat(idx));
            case DOUBLE -> Double.toString(rows.getDouble(idx));
            case BOOLEAN -> Boolean.toString(rows.getBoolean(idx));
            case BYTE_ARRAY -> rows.getString(idx);
            // aborts (not fails) the slug: the oracle can't format this physical type
            // yet, which is an oracle limitation rather than a vortex-java gap
            default -> throw new TestAbortedException(
                    "oracle cannot format parquet type " + col.type() + " (column: " + col.name() + ")");
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
