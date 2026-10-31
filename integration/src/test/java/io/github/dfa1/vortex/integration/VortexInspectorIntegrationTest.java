package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.inspect.ColumnDistribution;
import io.github.dfa1.vortex.inspect.ColumnProfile;
import io.github.dfa1.vortex.inspect.HtmlReport;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies that VortexInspector produces a correct report for a JNI-written file.
class VortexInspectorIntegrationTest {

    @SuppressWarnings("SameParameterValue")
    @Test
    void inspect_showsFileInfoAndEncodings(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("inspect.vtx");
        JniFiles.writeIdsAndRandomValues(file, 50_000);

        // When
        String result;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = VortexInspector.inspect(vf);
        }

        // Then
        System.out.println(result);
        assertThat(result)
                .contains("Vortex v")
                .contains("id")
                .contains("value")
                .contains("Registered encodings:")
                .contains("Used encodings:")
                .contains("Layout:");
    }

    @Test
    void inspect_reportsMinMaxFromZoneMapTable(@TempDir Path tmp) throws IOException {
        // Given — the Rust writer puts bounds only in the vortex.zoned stats table, never in the
        // flat segments' array-level stats; the inspector used to read only the latter and
        // printed no min/max for any Rust-written file (#416). ids are 0..rows-1, so the
        // expected bounds are exact.
        Path file = tmp.resolve("zoned.vtx");
        JniFiles.writeIdsAndRandomValues(file, 50_000);

        // When
        String result;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = VortexInspector.inspect(vf);
        }

        // Then
        assertThat(result).containsPattern("id: .*min=0 max=49999");
    }

    @Test
    void htmlReport_reportsChunkMinMaxFromZoneMapTable(@TempDir Path tmp) throws IOException {
        // Given — same #416 gap in the HTML report: its per-chunk table read only array-level
        // stats, so a Rust-written single-chunk column showed empty min/max cells.
        Path file = tmp.resolve("zoned.vtx");
        JniFiles.writeIdsAndRandomValues(file, 50_000);

        // When
        String result;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = HtmlReport.render(InspectorTree.build(vf), "zoned.vtx");
        }

        // Then
        assertThat(result).contains("<td><code>0</code></td><td><code>49999</code></td>");
    }

    @Test
    void htmlReport_globalDictColumn_chunkMinMaxAreValuesNotCodes(@TempDir Path tmp) throws IOException {
        // Given — a global-dictionary column stores per-chunk *codes* under its zone map; their
        // array-level min/max are code numbers (0, 1, ...), not values. Chunk 0 holds only
        // "apple"/"banana" and chunk 1 only "cherry"/"date", so each chunk row must show its own
        // value range, which only the zone-map table carries. The legacy vortex.stats layout (an
        // edition before core2026.08.0) zones per batch; default vortex.zoned zones per 8192 rows.
        Path file = tmp.resolve("dict.vtx");
        ColumnName fruit = ColumnName.of("fruit");
        var schema = new DType.Struct(List.of(fruit), List.of(DType.UTF8), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema,
                     WriteOptions.cascading(3).withEdition(io.github.dfa1.vortex.core.model.Editions.CORE_2025_10_0))) {
            writer.writeChunk(Map.of(fruit, repeat(1_000, "apple", "banana")));
            writer.writeChunk(Map.of(fruit, repeat(1_000, "cherry", "date")));
        }

        // When
        String result;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = HtmlReport.render(InspectorTree.build(vf), "dict.vtx");
        }

        // Then
        assertThat(result)
                .contains("<td><code>apple</code></td><td><code>banana</code></td>")
                .contains("<td><code>cherry</code></td><td><code>date</code></td>");
    }

    @Test
    void columnProfile_rustWrittenFile_matchesTheData(@TempDir Path tmp) throws IOException {
        // Given — the `gui` statistics table is folded from Rust's zone map alone, so a fold that
        // misreads it (a zone skipped, a bound taken as a value) shows a wrong range for real files
        Path file = tmp.resolve("zoned.vtx");
        JniFiles.writeIdsAndRandomValues(file, 50_000);
        var rng = new Random(42L);
        double minValue = Double.POSITIVE_INFINITY;
        double maxValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 50_000; i++) {
            double v = rng.nextDouble() * 1000.0;
            minValue = Math.min(minValue, v);
            maxValue = Math.max(maxValue, v);
        }

        // When
        List<ColumnProfile> result;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = ColumnProfile.of(vf);
        }

        // Then — Rust zones every 8192 rows: 50,000 rows make 7 zones, one sparkline point each
        assertThat(result).extracting(ColumnProfile::name).containsExactly("id", "value");
        ColumnProfile id = result.getFirst();
        assertThat(id.min()).isEqualTo(0L);
        assertThat(id.max()).isEqualTo(49_999L);
        assertThat(id.nullCount()).isZero();
        assertThat(id.zones()).hasSize(7);
        ColumnProfile value = result.get(1);
        assertThat(value.min()).isEqualTo(minValue);
        assertThat(value.max()).isEqualTo(maxValue);
    }

    @Test
    void columnProfile_globalDictColumn_rangeFromValuesNotCodes(@TempDir Path tmp) throws IOException {
        // Given — the default vortex.zoned map records only truncated bounds for a string, so the
        // profile takes min/max from each chunk's own stats; under a dict layout those are the
        // values pool's. The pool's sum and null count describe the dictionary, not the rows, and
        // must not leak in: four distinct values against 2,000 rows
        Path file = tmp.resolve("dict.vtx");
        ColumnName fruit = ColumnName.of("fruit");
        var schema = new DType.Struct(List.of(fruit), List.of(DType.UTF8), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            writer.writeChunk(Map.of(fruit, repeat(1_000, "apple", "banana")));
            writer.writeChunk(Map.of(fruit, repeat(1_000, "cherry", "date")));
        }

        // When
        ColumnProfile result;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = ColumnProfile.of(vf).getFirst();
        }

        // Then
        assertThat(result.min()).isEqualTo("apple");
        assertThat(result.max()).isEqualTo("date");
        assertThat(result.nullCount()).isZero();
        assertThat(result.sum()).isNull();
    }

    @Test
    void columnDistribution_rustWrittenFile_totalsAndHistogramMatchTheData(@TempDir Path tmp) throws IOException {
        // Given — 0..49,999 in `id`: its total is known, and 50,000 rows in 30 equal bins over
        // [0, 49999] put 1,666 or 1,667 rows in each, so a bin that drops or double-counts a chunk
        // shows up
        Path file = tmp.resolve("zoned.vtx");
        JniFiles.writeIdsAndRandomValues(file, 50_000);
        var rng = new Random(42L);
        double valueSum = 0;
        for (int i = 0; i < 50_000; i++) {
            valueSum += rng.nextDouble() * 1000.0;
        }

        // When
        ColumnDistribution id;
        ColumnDistribution value;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<ColumnProfile> profiles = ColumnProfile.of(vf);
            id = ColumnDistribution.of(vf, profiles.getFirst());
            value = ColumnDistribution.of(vf, profiles.get(1));
        }

        // Then
        assertThat(id.rows()).isEqualTo(50_000);
        assertThat(id.nulls()).isZero();
        assertThat(id.sum()).isEqualTo(49_999L * 50_000L / 2);
        assertThat(id.distinct()).isNull();
        assertThat(id.histogram()).isTrue();
        assertThat(id.bars()).hasSize(30)
                .allSatisfy(bin -> assertThat(bin.count()).isBetween(1_666L, 1_667L));
        assertThat(value.sum().doubleValue()).isCloseTo(valueSum, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(value.bars().stream().mapToLong(ColumnDistribution.Bar::count).sum()).isEqualTo(50_000);
    }

    @Test
    void columnDistribution_dictColumn_countsEveryValue(@TempDir Path tmp) throws IOException {
        // Given — a dictionary column is counted off its codes; each value must still get its own
        // rows, not its code's or another chunk's
        Path file = tmp.resolve("dict.vtx");
        ColumnName fruit = ColumnName.of("fruit");
        var schema = new DType.Struct(List.of(fruit), List.of(DType.UTF8), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            writer.writeChunk(Map.of(fruit, repeat(1_000, "apple", "banana")));
            writer.writeChunk(Map.of(fruit, repeat(3_000, "cherry", "apple")));
        }

        // When
        ColumnDistribution result;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            result = ColumnDistribution.of(vf, ColumnProfile.of(vf).getFirst());
        }

        // Then — most frequent first
        assertThat(result.distinct()).isEqualTo(3L);
        assertThat(result.bars()).containsExactly(
                new ColumnDistribution.Bar("apple", null, 2_000),
                new ColumnDistribution.Bar("cherry", null, 1_500),
                new ColumnDistribution.Bar("banana", null, 500));
        assertThat(result.lengths()).isEqualTo(new ColumnDistribution.Lengths(5, 6, 2_000L * 5 + 1_500L * 6 + 500L * 6));
    }

    @Test
    void peek_readsSortednessRustRecordsInFlatArrayStats(@TempDir Path tmp) throws IOException {
        // Given — Rust records is_sorted/is_strict_sorted in a flat segment's array stats for an
        // ascending integer column. Java's ArrayStats used to drop both (and carried a trueCount
        // the wire has no field for), so these read as unknown.
        Path file = tmp.resolve("sorted.vtx");
        JniFiles.writeIdsAndRandomValues(file, 50_000);

        // When
        List<ArrayStats> result = new ArrayList<>();
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            InspectorTree tree = InspectorTree.buildShallow(vf);
            InspectorTree.Node id = tree.root().children().stream()
                    .filter(c -> c.fieldName().equals(Optional.of("id"))).findFirst().orElseThrow();
            for (InspectorTree.Node flat : dataFlats(id)) {
                result.add(InspectorTree.peek(flat, vf).stats());
            }
        }

        // Then
        assertThat(result).isNotEmpty().allSatisfy(stats -> {
            assertThat(stats.isSorted()).isTrue();
            assertThat(stats.isStrictSorted()).isTrue();
        });
    }

    /// The flat segments holding a column's values: every chunk of a chunked node, else the first
    /// child (a zoned node's second child is its zone-map table, not data).
    private static List<InspectorTree.Node> dataFlats(InspectorTree.Node node) {
        if (node.layout().isFlat()) {
            return List.of(node);
        }
        if (node.layout().isChunked()) {
            return node.children().stream().flatMap(c -> dataFlats(c).stream()).toList();
        }
        return dataFlats(node.children().getFirst());
    }

    private static String[] repeat(int rows, String a, String b) {
        String[] out = new String[rows];
        for (int i = 0; i < rows; i++) {
            out[i] = i % 2 == 0 ? a : b;
        }
        return out;
    }
}
