package io.github.dfa1.vortex.integration;

import de.siegmar.fastcsv.writer.CsvWriter;
import dev.hardwood.InputFile;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.row.PqList;
import dev.hardwood.row.PqStruct;
import dev.hardwood.schema.SchemaNode;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.csv.CsvExporter;
import io.github.dfa1.vortex.csv.ExportOptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.opentest4j.TestAbortedException;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
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
///
/// Tagged `raincloud` and excluded from the default `./mvnw verify` (via the failsafe
/// `excludedGroups` configuration) so a routine build never runs the real corpus even on
/// a developer machine that has hydrated it locally. The exclusion applies even to an
/// explicit `-Dit.test=RaincloudConformanceIntegrationTest` selection, so running it
/// requires clearing the tag filter first: `-Dvortex.it.excludedGroups=
/// -Dit.test=RaincloudConformanceIntegrationTest`.
@Tag("raincloud")
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
    @Execution(ExecutionMode.CONCURRENT)
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
                        // missing_auth slugs never reach here in practice — hydration fails
                        // before a manifest entry exists — but report the same as untriaged
                        // rather than falling through to assertStillFails (wrong: that path
                        // means "known reader gap", not "couldn't even attempt this").
                        case "untriaged", "missing_auth" -> reportUntriaged(vortex, parquet);
                        default -> assertStillFails(vortex, parquet, status);
                    }
                }));
    }

    private static void assertMatchesParquetOracle(Path vortex, Path parquet) throws IOException {
        // Given — both producers run in parallel via virtual threads; compare line-by-line
        // without temp files, cutting wall time roughly in half for large slugs.
        AtomicReference<Throwable> oracleError = new AtomicReference<>();
        AtomicReference<Throwable> vortexError = new AtomicReference<>();
        PipedWriter oracleSink = new PipedWriter();
        PipedWriter vortexSink = new PipedWriter();
        try (BufferedReader oracleReader = new BufferedReader(new PipedReader(oracleSink, 1 << 17));
             BufferedReader vortexReader = new BufferedReader(new PipedReader(vortexSink, 1 << 17))) {

            Thread oracleThread = Thread.ofVirtual().start(() -> {
                try (oracleSink) {
                    writeOracleCsv(parquet, oracleSink);
                } catch (Throwable t) {
                    oracleError.set(t);
                }
            });
            Thread vortexThread = Thread.ofVirtual().start(() -> {
                try (vortexSink) {
                    CsvExporter.exportCsv(vortex, vortexSink, ExportOptions.defaults());
                } catch (Throwable t) {
                    vortexError.set(t);
                }
            });

            // When / Then
            Throwable mainError = null;
            try {
                assertFilesMatch(vortexReader, oracleReader);
            } catch (Throwable t) {
                mainError = t;
            } finally {
                // assertFilesMatch may stop reading early (oracle abort, line mismatch) while a
                // producer is still writing — closing the read end here, rather than relying on
                // the outer try-with-resources (which only runs after join() below), unblocks a
                // producer parked on a full PipedWriter buffer with a "Pipe closed" IOException
                // instead of hanging join() forever (#259).
                closeQuietly(vortexReader);
                closeQuietly(oracleReader);
            }

            try {
                oracleThread.join();
                vortexThread.join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            // Propagate in priority order: decode gap > oracle abort > mismatch.
            // Oracle abort ranks above mismatch because a spurious AssertionError("oracle ended
            // before vortex output") is produced whenever the oracle aborts before writing all
            // rows — that is an oracle limitation, not a conformance failure.
            Throwable oe = oracleError.get();
            Throwable ve = vortexError.get();
            if (ve instanceof VortexException e) {
                throw e;
            }
            if (ve instanceof IndexOutOfBoundsException e) {
                throw e;
            }
            if (oe instanceof TestAbortedException e) {
                throw e;
            }
            if (mainError instanceof AssertionError e) {
                throw e;
            }
            if (oe != null) {
                throw new TestAbortedException("oracle cannot read the parquet sibling: " + oe);
            }
            if (ve instanceof IOException e) {
                throw e;
            }
            if (ve instanceof RuntimeException e) {
                throw e;
            }
            if (ve != null) {
                throw new RuntimeException(ve);
            }
            if (mainError instanceof IOException e) {
                throw e;
            }
            if (mainError instanceof RuntimeException e) {
                throw e;
            }
            if (mainError != null) {
                throw new RuntimeException(mainError);
            }
        }
    }

    /// Closes a stream ignoring the outcome, used only to unblock a producer thread
    /// parked writing to the other end of a pipe before joining it.
    ///
    /// @param closeable the stream to close
    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException _) {
            // best effort: only used to unblock a producer thread before join()
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

    /// Stream-compares two CSV sources line by line without loading either into heap.
    ///
    /// @param result the vortex-java export reader
    /// @param oracle the parquet oracle reader
    /// @throws AssertionError if any line differs or the sources have different lengths
    /// @throws IOException    if either reader throws
    private static void assertFilesMatch(BufferedReader result, BufferedReader oracle) throws IOException {
        long lineNum = 0;
        String rLine;
        while ((rLine = result.readLine()) != null) {
            lineNum++;
            String oLine = oracle.readLine();
            assertThat(oLine).as("oracle ended before vortex output at line %d", lineNum).isNotNull();
            assertThat(rLine).as("line %d", lineNum).isEqualTo(oLine);
        }
        assertThat(oracle.readLine()).as("vortex output ended before oracle at line %d", lineNum + 1).isNull();
    }

    /// @param result the vortex-java export file
    /// @param oracle the parquet oracle file
    /// @throws AssertionError if any line differs or the files have different lengths
    /// @throws IOException    if either file cannot be read
    private static void assertFilesMatch(Path result, Path oracle) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(result);
             BufferedReader o = Files.newBufferedReader(oracle)) {
            assertFilesMatch(r, o);
        }
    }

    /// Writes the parquet oracle to a file using the same cell rules as `CsvExporter`.
    /// An oracle-side failure (unsupported physical type, MAP column) aborts the
    /// slug via [TestAbortedException] rather than failing it — it says nothing about
    /// vortex-java. An `ok` slug whose parquet cannot be read stops being verified
    /// (visibly, as skipped) — widen the oracle rather than let unverifiable entries
    /// fail the build.
    ///
    /// @param parquet the parquet sibling
    /// @param out     the output file to write CSV into
    /// @throws TestAbortedException if the oracle cannot read this parquet
    private static void writeOracle(Path parquet, Path out) {
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

            // One CSV cell per top-level field, mirroring CsvExporter's one-cell-per-Vortex-column
            // model — not one per leaf column, which for a LIST/STRUCT field would emit multiple
            // cells for what CsvExporter renders as a single JSON array/object cell (#261).
            List<SchemaNode> topLevel = pfr.getFileSchema().getRootNode().children();

            // De-duplicate duplicate column names with the Rust Vortex writer's algorithm:
            // the Nth (N >= 1) occurrence of a base name gets a " [N]" suffix, matching
            // the de-duplicated names in the Vortex file (#256).
            Map<String, Integer> seen = new LinkedHashMap<>();
            List<String> header = new ArrayList<>(topLevel.size());
            for (SchemaNode node : topLevel) {
                int count = seen.merge(node.name(), 1, Integer::sum) - 1;
                header.add(count == 0 ? node.name() : node.name() + " [" + count + "]");
            }
            csv.writeRecord(header);

            String[] row = new String[topLevel.size()];
            while (rows.hasNext()) {
                rows.next();
                for (int c = 0; c < topLevel.size(); c++) {
                    row[c] = oracleCell(topLevel.get(c), rows, c);
                }
                csv.writeRecord(row);
            }
        }
    }

    /// Formats one top-level field with the exact rules of `CsvExporter.cellValue`: a null field
    /// renders as an empty CSV field, a scalar leaf uses the JDK canonical `toString` of the
    /// value, and a LIST/STRUCT field renders as a JSON array/object cell via
    /// [#oracleJsonValue(SchemaNode, Object)].
    ///
    /// Field access is by index — the field's position among top-level schema children, per
    /// `dev.hardwood.row.StructAccessor`'s "projected schema order" — rather than by name, so
    /// that files with duplicate column names (#256) read the right field.
    ///
    /// INT32/INT64 leaves with a `UINT_32`/`UINT_64` logical-type annotation are treated as
    /// unsigned so their string representation matches the U32/U64 Vortex columns that carry the
    /// same bits (#253); this applies at any nesting depth, not only top-level scalars.
    ///
    /// @param node       the top-level field's schema node
    /// @param rows       the row reader positioned at the current row
    /// @param fieldIndex the field's position among top-level schema children
    /// @return the formatted cell string
    private static String oracleCell(SchemaNode node, RowReader rows, int fieldIndex) {
        if (rows.isNull(fieldIndex)) {
            return "";
        }
        if (node instanceof SchemaNode.GroupNode group) {
            if (group.isList()) {
                return oracleJsonArray(group, rows.getList(fieldIndex));
            }
            if (group.isMap()) {
                throw new TestAbortedException("oracle cannot format MAP column: " + group.name());
            }
            // hardwood's own NestedRowReader.getStruct() mis-casts a VARIANT-shredded group
            // (metadata/value/typed_value) to its plain-Struct FieldDesc and throws
            // ClassCastException — an oracle bug, not a vortex-java gap. Abort before hitting it.
            if (group.isVariant()) {
                throw new TestAbortedException("oracle cannot format VARIANT column: " + group.name());
            }
            return oracleJsonObject(group, rows.getStruct(fieldIndex));
        }
        SchemaNode.PrimitiveNode prim = (SchemaNode.PrimitiveNode) node;
        boolean unsignedInt = isUnsignedInt(prim);
        return switch (prim.type()) {
            case INT32 -> unsignedInt ? Integer.toUnsignedString(rows.getInt(fieldIndex)) : Integer.toString(rows.getInt(fieldIndex));
            case INT64 -> unsignedInt ? Long.toUnsignedString(rows.getLong(fieldIndex)) : Long.toString(rows.getLong(fieldIndex));
            case FLOAT -> Float.toString(rows.getFloat(fieldIndex));
            case DOUBLE -> Double.toString(rows.getDouble(fieldIndex));
            case BOOLEAN -> Boolean.toString(rows.getBoolean(fieldIndex));
            case BYTE_ARRAY -> rows.getString(fieldIndex);
            // aborts (not fails) the slug: the oracle can't format this physical type
            // yet, which is an oracle limitation rather than a vortex-java gap
            default -> throw new TestAbortedException(
                    "oracle cannot format parquet type " + prim.type() + " (column: " + prim.name() + ")");
        };
    }

    /// Renders a nested struct value as a JSON object `{"field":value,...}` with fields in
    /// schema order, mirroring `CsvExporter.jsonObject`.
    private static String oracleJsonObject(SchemaNode.GroupNode structNode, PqStruct struct) {
        List<SchemaNode> fields = structNode.children();
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            oracleJsonString(sb, fields.get(i).name());
            sb.append(':').append(oracleJsonValue(fields.get(i), struct.getValue(i)));
        }
        return sb.append('}').toString();
    }

    /// Renders a nested list value as a JSON array `[v0,v1,...]`, mirroring `CsvExporter.jsonArray`.
    private static String oracleJsonArray(SchemaNode.GroupNode listNode, PqList list) {
        SchemaNode element = listNode.getListElement();
        int size = list.size();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(oracleJsonValue(element, list.get(i)));
        }
        return sb.append(']').toString();
    }

    /// Renders one decoded struct field / list element as JSON text, mirroring
    /// `CsvExporter.jsonValue`: a nested object/array for STRUCT/LIST, a quoted escaped string for
    /// STRING, JSON `null` for a null value, and the bare JDK `toString` for numbers/booleans —
    /// unsigned for an INT32/INT64 `node` with a `UINT_*` logical-type annotation.
    ///
    /// @param node  the value's schema node (its element/field type)
    /// @param value the decoded value, from [PqStruct#getValue(int)] or [PqList#get(int)]
    /// @return the rendered JSON text
    private static String oracleJsonValue(SchemaNode node, Object value) {
        if (value == null) {
            return "null";
        }
        return switch (value) {
            case PqStruct struct -> oracleJsonObject((SchemaNode.GroupNode) node, struct);
            case PqList list -> oracleJsonArray((SchemaNode.GroupNode) node, list);
            case String s -> {
                StringBuilder sb = new StringBuilder();
                oracleJsonString(sb, s);
                yield sb.toString();
            }
            case Integer i -> isUnsignedInt(node) ? Integer.toUnsignedString(i) : Integer.toString(i);
            case Long l -> isUnsignedInt(node) ? Long.toUnsignedString(l) : Long.toString(l);
            case Float f -> Float.toString(f);
            case Double d -> Double.toString(d);
            case Boolean b -> Boolean.toString(b);
            default -> throw new TestAbortedException(
                    "oracle cannot format nested value of type " + value.getClass().getSimpleName());
        };
    }

    /// Whether `node` is an INT32/INT64 leaf with a `UINT_*` logical-type annotation (#253):
    /// `false` for any other node, including `null` (an unresolvable list element schema).
    private static boolean isUnsignedInt(SchemaNode node) {
        return node instanceof SchemaNode.PrimitiveNode prim
                && prim.logicalType() instanceof LogicalType.IntType lt && !lt.isSigned();
    }

    /// Appends `value` to `sb` as a JSON string literal, mirroring `CsvExporter.jsonString`:
    /// wrapped in double quotes with `"`, backslash, the standard short escapes, and any other
    /// control character below `U+0020` escaped as `\\uXXXX`.
    private static void oracleJsonString(StringBuilder sb, String value) {
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
