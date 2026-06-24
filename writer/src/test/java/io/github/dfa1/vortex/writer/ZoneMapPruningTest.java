package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.LongArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Round-trip tests verifying zone-map chunk pruning via RowFilter.
class ZoneMapPruningTest {

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of("id"),
            List.of(DType.I64),
            false);

    private static final DType.Struct U64_SCHEMA = new DType.Struct(
            List.of("id"),
            List.of(DType.U64),
            false);

    // Three chunks: id in [1..50], [51..100], [101..150]
    private static Path writeThreeChunks(Path tmp) throws IOException {
        Path file = tmp.resolve("three_chunks.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("id", range(1L, 50L)));
            sut.writeChunk(Map.of("id", range(51L, 100L)));
            sut.writeChunk(Map.of("id", range(101L, 150L)));
        }
        return file;
    }

    /// Returns one entry per surviving chunk: its row count after filter pruning.
    private static List<Long> scanRowCounts(Path file, RowFilter filter) throws IOException {
        var opts = new ScanOptions(List.of(), filter, ScanOptions.NO_LIMIT);
        var registry = registry();
        var rowCounts = new ArrayList<Long>();
        try (var vf = VortexReader.open(file, registry);
             var iter = vf.scan(opts)) {
            iter.forEachRemaining(c -> rowCounts.add(c.rowCount()));
        }
        return rowCounts;
    }

    private static long[] range(long from, long to) {
        long[] arr = new long[(int) (to - from + 1)];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = from + i;
        }
        return arr;
    }

    private static Path writeI64Chunk(Path tmp, long... values) throws IOException {
        Path file = tmp.resolve("i64_one.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("id", values));
        }
        return file;
    }

    // Service-loaded so any encoding the writer picks (e.g. ALP for the F32 column) decodes; the
    // tests exercise zone-map pruning, not a specific decoder.
    private static ReadRegistry registry() {
        return ReadRegistry.builder().registerServiceLoaded().build();
    }

    private static final DType.Struct F32_SCHEMA = new DType.Struct(
            List.of("v"),
            List.of(DType.F32),
            false);

    /// 2^63 as raw bits: `Long.MIN_VALUE` is the smallest *signed* long but 2^63 *unsigned*.
    private static final long TWO_POW_63 = Long.MIN_VALUE;

    // Two U64 chunks: a small chunk [10..20] and a chunk of values >= 2^63 (negative as a signed
    // long), so signed and unsigned ordering diverge.
    private static Path writeU64Chunks(Path tmp) throws IOException {
        Path file = tmp.resolve("u64_chunks.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, U64_SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("id", range(10L, 20L)));            // [10..20], 11 rows
            sut.writeChunk(Map.of("id", urange(TWO_POW_63 + 10, 11))); // [2^63+10 .. 2^63+20], 11 rows
        }
        return file;
    }

    private static long[] urange(long fromBits, int n) {
        long[] arr = new long[n];
        for (int i = 0; i < n; i++) {
            arr[i] = fromBits + i;
        }
        return arr;
    }

    // Three F32 chunks: v in [1..50], [51..100], [101..150]. F32 zone stats decode as Float, so a
    // Double or Float filter value must still compare via the shared width-agnostic path.
    private static Path writeF32Chunks(Path tmp) throws IOException {
        Path file = tmp.resolve("f32_chunks.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, F32_SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("v", f32range(1, 50)));
            sut.writeChunk(Map.of("v", f32range(51, 100)));
            sut.writeChunk(Map.of("v", f32range(101, 150)));
        }
        return file;
    }

    private static float[] f32range(int from, int to) {
        float[] arr = new float[to - from + 1];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = from + i;
        }
        return arr;
    }

    // Per-operator filter factories (value boxed by the caller), so the parameterized groups can
    // drive every comparison operator through one shared body.
    private static Function<Object, RowFilter> gt(String col) {
        return v -> RowFilter.gt(col, (Comparable<?>) v);
    }

    private static Function<Object, RowFilter> gte(String col) {
        return v -> RowFilter.gte(col, (Comparable<?>) v);
    }

    private static Function<Object, RowFilter> lt(String col) {
        return v -> RowFilter.lt(col, (Comparable<?>) v);
    }

    private static Function<Object, RowFilter> lte(String col) {
        return v -> RowFilter.lte(col, (Comparable<?>) v);
    }

    private static Function<Object, RowFilter> eq(String col) {
        return v -> RowFilter.eq(col, v);
    }

    private static Function<Object, RowFilter> neq(String col) {
        return v -> RowFilter.neq(col, v);
    }

    @Test
    void gte_prunesChunksBelowThreshold(@TempDir Path tmp) throws IOException {
        // Given — chunk 1 max=50, threshold=75 → chunk 1 pruned
        Path file = writeThreeChunks(tmp);

        // When
        List<Long> rowCounts = scanRowCounts(file, RowFilter.gte("id", 75L));

        // Then
        assertThat(rowCounts).containsExactly(50L, 50L); // chunk 2, 3
    }

    @Test
    void lte_prunesChunksAboveThreshold(@TempDir Path tmp) throws IOException {
        // Given — chunk 3 min=101, threshold=75 → chunk 3 pruned
        Path file = writeThreeChunks(tmp);

        // When
        List<Long> rowCounts = scanRowCounts(file, RowFilter.lte("id", 75L));

        // Then
        assertThat(rowCounts).containsExactly(50L, 50L); // chunk 1, 2
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Test
    void eq_prunesChunksExcludingValue(@TempDir Path tmp) throws IOException {
        // Given — id=75 only falls in chunk 2 [51..100]; chunks 1 and 3 pruned
        Path file = writeThreeChunks(tmp);

        // When
        List<Long> rowCounts = scanRowCounts(file, RowFilter.eq("id", 75L));

        // Then
        assertThat(rowCounts).containsExactly(50L); // chunk 2
    }

    @Test
    void and_prunesChunksExcludedByAnySubFilter(@TempDir Path tmp) throws IOException {
        // Given — AND(id>=51, id<=100): chunk 1 pruned by gte, chunk 3 pruned by lte
        Path file = writeThreeChunks(tmp);

        // When
        List<Long> rowCounts = scanRowCounts(file, RowFilter.and(
                RowFilter.gte("id", 51L),
                RowFilter.lte("id", 100L)));

        // Then
        assertThat(rowCounts).containsExactly(50L); // chunk 2
    }

    @Test
    void noFilter_returnsAllChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeThreeChunks(tmp);

        // When
        List<Long> rowCounts = scanRowCounts(file, null);

        // Then
        assertThat(rowCounts).hasSize(3);
    }

    /// Row-level filtering: within surviving chunks, only rows satisfying the predicate are collected.
    /// Zone-map pruning reduces the chunk set; row-level loops must still check each element.
    @Nested
    class RowLevel {

        @Test
        void eq_onlyExactMatchCollected(@TempDir Path tmp) throws IOException {
            // Given — id=75 lands in chunk 2 [51..100]; exactly one row matches
            Path file = writeThreeChunks(tmp);
            long predicate = 75L;
            var opts = ScanOptions.columns("id").withFilter(RowFilter.eq("id", predicate));

            // When
            List<Long> matched = collectMatching(file, opts, v -> v == predicate);

            // Then
            assertThat(matched).hasSize(1).allSatisfy(v -> assertThat(v).isEqualTo(predicate));
        }

        @Test
        void gte_allCollectedValuesAtOrAboveThreshold(@TempDir Path tmp) throws IOException {
            // Given — threshold=75: chunks 2 and 3 survive zone-map; rows below 75 in chunk 2 must not appear
            Path file = writeThreeChunks(tmp);
            long threshold = 75L;
            var opts = ScanOptions.columns("id").withFilter(RowFilter.gte("id", threshold));

            // When
            List<Long> matched = collectMatching(file, opts, v -> v >= threshold);

            // Then — 76 matching rows: 75..100 (26) + 101..150 (50)
            assertThat(matched).hasSize(76).allSatisfy(v -> assertThat(v).isGreaterThanOrEqualTo(threshold));
        }

        @Test
        void and_allCollectedValuesInsideRange(@TempDir Path tmp) throws IOException {
            // Given — AND(id>=60, id<=90): only chunk 2 survives zone-map; 31 rows match
            Path file = writeThreeChunks(tmp);
            long lo = 60L, hi = 90L;
            var opts = ScanOptions.columns("id").withFilter(
                    RowFilter.and(RowFilter.gte("id", lo), RowFilter.lte("id", hi)));

            // When
            List<Long> matched = collectMatching(file, opts, v -> v >= lo && v <= hi);

            // Then
            assertThat(matched).hasSize(31)
                    .allSatisfy(v -> assertThat(v).isBetween(lo, hi));
        }

        private List<Long> collectMatching(Path file, ScanOptions opts, java.util.function.LongPredicate predicate)
                throws IOException {
            var result = new ArrayList<Long>();
            try (VortexReader vf = VortexReader.open(file, registry());
                 var iter = vf.scan(opts)) {
                while (iter.hasNext()) {
                    try (Chunk c = iter.next()) {
                        LongArray col = c.column("id");
                        for (long i = 0; i < col.length(); i++) {
                            long v = col.getLong(i);
                            if (predicate.test(v)) {
                                result.add(v);
                            }
                        }
                    }
                }
            }
            return result;
        }
    }

    // ── #159: width-agnostic + unsigned-aware zone-map comparator ──────────────

    /// Every operator must prune identically whether the filter value is boxed at the column's
    /// natural width (`Integer`) or the stat's storage width (`Long`) — the core of #159. `Eq`/`Neq`
    /// previously used their own swallow-on-mismatch comparator, so they are covered here too.
    @Nested
    class BoxedWidth {

        static Stream<Arguments> ops() {
            return Stream.of(
                    arguments("gt 75", gt("id"), List.of(50L, 50L)),
                    arguments("gte 75", gte("id"), List.of(50L, 50L)),
                    arguments("lt 75", lt("id"), List.of(50L, 50L)),
                    arguments("lte 75", lte("id"), List.of(50L, 50L)),
                    arguments("eq 75", eq("id"), List.of(50L)),
                    arguments("neq 75", neq("id"), List.of(50L, 50L, 50L)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ops")
        void integerValuePrunesLikeLong(String name, Function<Object, RowFilter> make, List<Long> expected,
                @TempDir Path tmp) throws IOException {
            // Given — id in [1..50],[51..100],[101..150]
            Path file = writeThreeChunks(tmp);

            // When — the same numeric value, boxed first as Long (stat width) then as Integer
            List<Long> asLong = scanRowCounts(file, make.apply(75L));
            List<Long> asInt = scanRowCounts(file, make.apply(75));

            // Then — both prune to the expected survivors (before #159 the Integer case pruned nothing)
            assertThat(asLong).containsExactlyElementsOf(expected);
            assertThat(asInt).containsExactlyElementsOf(expected);
        }
    }

    /// `U64` stats and values store the raw 64 bits, so a value `>= 2^63` is a negative `long`.
    /// Pruning must use unsigned ordering, or it silently keeps/drops the wrong chunks (#159 follow-up).
    @Nested
    class Unsigned {

        static Stream<Arguments> cases() {
            // Two U64 chunks: low [10..20], high [2^63+10 .. 2^63+20]. A signed compare reads the
            // high chunk's bits as negative, so it would keep/drop the wrong chunk in each case.
            return Stream.of(
                    arguments("gte 15 keeps both", RowFilter.gte("id", 15L), List.of(11L, 11L)),
                    arguments("gt 2^63+15 keeps high only", RowFilter.gt("id", TWO_POW_63 + 15), List.of(11L)),
                    arguments("lte 15 keeps low only", RowFilter.lte("id", 15L), List.of(11L)),
                    arguments("eq 2^63+15 in high only", RowFilter.eq("id", TWO_POW_63 + 15), List.of(11L)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cases")
        void prunesByUnsignedOrder(String name, RowFilter filter, List<Long> expected, @TempDir Path tmp)
                throws IOException {
            // Given
            Path file = writeU64Chunks(tmp);

            // When
            List<Long> rowCounts = scanRowCounts(file, filter);

            // Then — surviving chunks match unsigned semantics (signed compare would differ)
            assertThat(rowCounts).containsExactlyElementsOf(expected);
        }
    }

    /// `F32` stats decode as `Float`; a `Double` or `Float` filter value must compare via the
    /// width-agnostic float path rather than throw or fail to prune.
    @Nested
    class FloatWidths {

        static Stream<Arguments> values() {
            // gte(75) against F32 chunks [1..50],[51..100],[101..150]: chunk 1 (max 50) pruned,
            // regardless of whether the filter value is boxed as Double or Float.
            return Stream.of(
                    arguments("Double value", 75.0),
                    arguments("Float value", 75.0f));
        }

        @ParameterizedTest(name = "{0} on F32 column")
        @MethodSource("values")
        void f32ColumnPrunesRegardlessOfFilterWidth(String name, Object value, @TempDir Path tmp)
                throws IOException {
            // Given
            Path file = writeF32Chunks(tmp);

            // When
            List<Long> rowCounts = scanRowCounts(file, RowFilter.gte("v", (Comparable<?>) value));

            // Then — chunk 1 (max 50) pruned
            assertThat(rowCounts).containsExactly(50L, 50L);
        }
    }

    /// Comparison mode keys off the *column* type, not the filter value's boxing — so an integer
    /// column never routes through double-compare, which would lose precision past 2^53.
    @Nested
    class IntegerColumnFloatFilter {

        @Test
        void doubleFilterOnI64ComparesInIntegerDomain(@TempDir Path tmp) throws IOException {
            // Given — a chunk whose max (2^53 + 1) is NOT exactly representable as a double
            long max = (1L << 53) + 1;
            Path file = writeI64Chunk(tmp, max - 2, max - 1, max);

            // When — gt with a Double threshold of 2^53; only the row 2^53+1 satisfies it
            List<Long> rowCounts = scanRowCounts(file, RowFilter.gt("id", (double) (1L << 53)));

            // Then — chunk kept. A double-domain compare would round 2^53+1 down to 2^53 and wrongly
            // prune the chunk, dropping the one matching row.
            assertThat(rowCounts).containsExactly(3L);
        }
    }

    /// A filter value genuinely incomparable to the column's stat (e.g. a `String` against a numeric
    /// column) is a caller error: surface it, never swallow it into a silent no-prune (#159).
    @Nested
    class TypeMismatch {

        static Stream<Arguments> ops() {
            return Stream.of(
                    arguments("gt", gt("id")), arguments("gte", gte("id")),
                    arguments("lt", lt("id")), arguments("lte", lte("id")),
                    arguments("eq", eq("id")), arguments("neq", neq("id")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ops")
        void stringValueOnNumericColumn_throws(String name, Function<Object, RowFilter> make,
                @TempDir Path tmp) throws IOException {
            // Given
            Path file = writeThreeChunks(tmp);

            // When / Then
            assertThatThrownBy(() -> scanRowCounts(file, make.apply("not-a-number")))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("not comparable");
        }
    }
}
