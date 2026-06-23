package io.github.dfa1.vortex.integration.load;

import static org.assertj.core.api.Assertions.assertThat;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import de.siegmar.fastcsv.writer.CsvWriter;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.csv.CsvExporter;
import io.github.dfa1.vortex.csv.CsvImporter;
import io.github.dfa1.vortex.csv.ExportOptions;
import io.github.dfa1.vortex.csv.ImportOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/// CSV → Vortex → CSV round-trip load test.
///
/// The data source is a **deterministic seeded generator**: row `i` is fully
/// determined by [#SEED] and `i`, so it doubles as the oracle — the exported
/// rows are compared back against the regenerated rows, never against a stored
/// copy. This catches both import and export defects.
///
/// Two tests share the same pipeline:
/// - [#roundTrip_small_validatesLogic()] — 1 000 rows, runs in every
///   `verify -pl integration` so the generator/diff logic stays covered.
/// - [#roundTrip_load_dailyCron()] — gated on `-Dvortex.load.rows=<n>`; the
///   daily workflow runs it at ~100 M rows (~10 GB). Files go to
///   `-Dvortex.load.dir` (the large `/mnt` disk on GitHub runners).
///
/// Byte-exact round-trip holds because the generator emits only canonical
/// forms that survive parse→store→`toString`: `Long.toString` (I64),
/// `Double.toString` (F64), `Boolean.toString` (Bool), plain ASCII (Utf8).
/// [CsvExporter] reproduces exactly those forms.
class LargeCsvRoundTripLoadIntegrationTest {

    private static final long SEED = 0x5DEECE66DL;

    /// Schema is fixed (not inferred) so column types never drift with the
    /// sampled first chunk. Field names double as the CSV header.
    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of("id", "a", "b", "x", "y", "flag", "sym", "note"),
            List.of(
                    DType.I64,
                    DType.I64,
                    DType.I64,
                    DType.F64,
                    DType.F64,
                    DType.BOOL,
                    DType.UTF8,
                    DType.UTF8),
            false);

    private static final String[] HEADER = SCHEMA.fieldNames().toArray(String[]::new);

    /// Low-cardinality symbol set — plain ASCII, no delimiter/quote chars.
    private static final String[] SYMS = {"AAPL", "MSFT", "GOOG", "AMZN", "META", "NVDA"};

    @Test
    void roundTrip_small_validatesLogic(@TempDir Path dir) throws IOException {
        // Given a 1 000-row deterministic CSV
        // When imported to Vortex and exported back
        // Then every exported row equals the regenerated oracle row
        runRoundTrip(dir, 1_000);
    }

    @Test
    @EnabledIfSystemProperty(named = "vortex.load.rows", matches = "\\d+")
    void roundTrip_load_dailyCron() throws IOException {
        // Given the row count and scratch dir supplied by the daily workflow
        long rows = Long.getLong("vortex.load.rows");
        Path dir = Path.of(System.getProperty("vortex.load.dir", System.getProperty("java.io.tmpdir")));

        // When the full CSV → Vortex → CSV round-trip runs at load scale
        // Then every one of the ~100 M exported rows matches the oracle
        runRoundTrip(dir, rows);
    }

    /// Generates the CSV, imports it, exports it, and asserts the exported
    /// rows equal the regenerated oracle rows. Scratch files are deleted in a
    /// `finally` so a 10 GB run does not leak disk between attempts.
    private static void runRoundTrip(Path dir, long rows) throws IOException {
        Files.createDirectories(dir);
        Path csvIn = dir.resolve("load-in.csv");
        Path vtx = dir.resolve("load.vtx");
        Path csvOut = dir.resolve("load-out.csv");
        try {
            // Given a deterministic CSV written through the same CSV library
            generateCsv(csvIn, rows);

            // When imported to Vortex (explicit schema, no inference) and exported back
            CsvImporter.importCsv(csvIn, vtx, ImportOptions.defaults().withSchema(SCHEMA));
            CsvExporter.exportCsv(vtx, csvOut, ExportOptions.defaults());

            // Then the export reproduces the oracle row-for-row
            long result = assertEqualsOracle(csvOut, rows);
            assertThat(result).isEqualTo(rows);
        } finally {
            Files.deleteIfExists(csvOut);
            Files.deleteIfExists(vtx);
            Files.deleteIfExists(csvIn);
        }
    }

    private static void generateCsv(Path path, long rows) throws IOException {
        try (CsvWriter writer = CsvWriter.builder().build(path)) {
            writer.writeRecord(HEADER);
            for (long i = 0; i < rows; i++) {
                writer.writeRecord(row(i));
            }
        }
    }

    /// Streams the exported CSV and compares each record to the regenerated
    /// oracle row, failing fast with the offending row/column. Returns the
    /// number of data rows read so the caller can assert the total.
    private static long assertEqualsOracle(Path exported, long rows) throws IOException {
        try (CsvReader<CsvRecord> reader = CsvReader.builder().ofCsvRecord(exported)) {
            Iterator<CsvRecord> it = reader.iterator();
            if (!it.hasNext()) {
                throw new AssertionError("exported CSV is empty");
            }
            List<String> header = it.next().getFields();
            if (!header.equals(SCHEMA.fieldNames())) {
                throw new AssertionError("header mismatch: " + header + " != " + SCHEMA.fieldNames());
            }
            long i = 0;
            while (it.hasNext()) {
                String[] expected = row(i);
                CsvRecord record = it.next();
                for (int c = 0; c < expected.length; c++) {
                    String actual = record.getField(c);
                    if (!expected[c].equals(actual)) {
                        throw new AssertionError(
                                "mismatch at row " + i + " col " + SCHEMA.fieldNames().get(c)
                                        + ": expected '" + expected[c] + "' got '" + actual + "'");
                    }
                }
                i++;
            }
            if (i != rows) {
                throw new AssertionError("row count mismatch: exported " + i + " expected " + rows);
            }
            return i;
        }
    }

    /// Builds row `i` as the canonical field strings the exporter must
    /// reproduce. Field values are independent draws from a SplitMix64 stream
    /// keyed by `(i, column)`, so any row is reproducible without state.
    private static String[] row(long i) {
        long a = mix(i, 1);
        long b = mix(i, 2);
        long x = mix(i, 3);
        long y = mix(i, 4);
        long flag = mix(i, 5);
        long sym = mix(i, 6);
        return new String[] {
                Long.toString(i),
                Long.toString(a),
                Long.toString(b),
                Double.toString(finite(x)),
                Double.toString(finite(y)),
                Boolean.toString((flag & 1L) == 0L),
                SYMS[Math.floorMod(sym, SYMS.length)],
                "n" + i
        };
    }

    /// Maps a raw long to a finite, varied double that survives
    /// `parseDouble`→`toString` exactly (the F64 round-trip guarantee).
    private static double finite(long bits) {
        return (bits % 1_000_000_000L) / 1000.0;
    }

    /// SplitMix64 finalizer over `(SEED, i, column)` — independent, stateless
    /// per-cell values so the oracle can recompute any row on demand.
    private static long mix(long i, long column) {
        long z = SEED + i * 0x9E3779B97F4A7C15L + column * 0xC2B2AE3D27D4EB4FL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
