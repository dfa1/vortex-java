package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.ChunkedByteArray;
import io.github.dfa1.vortex.reader.array.DictLongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloat16Array;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.compute.Compute;
import io.github.dfa1.vortex.reader.compute.FilteredAggregate;
import io.github.dfa1.vortex.reader.compute.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Oracle test for the fused multi-column [Compute#filteredAggregate(Chunk, RowFilter, String)]
/// kernel: its one-pass result over a chunk must equal a brute-force row scan that evaluates the same
/// [RowFilter] (an n-ary `AND` of column-bound [Predicate] leaves) and folds the aggregate over the
/// selected non-null rows. The reference is hand-written from the SQL three-valued-logic semantics
/// (`==`/`<`/`<=` on the raw values, a null in a filter column failing that leaf, a null aggregate
/// value skipped), independent of the kernel's range-lowering, so any drift is a correctness bug.
///
/// The randomized cases build chunks of two nullable filter columns plus a nullable `i64`
/// aggregate, with a random `AND` of one-to-three leaves drawn from every predicate variant (the
/// comparisons, `BETWEEN`, and the two null tests). Each filter column randomly materializes as a
/// plain `i64` / `i32` array or as a dict-encoded view (a value pool behind flat or chunked `u8`
/// codes) — the same values either way — so the [io.github.dfa1.vortex.reader.compute] dict
/// code-scan lane is swept against the identical brute-force reference, on both the shapes that
/// drive it (a single comparison leaf) and the shapes it must decline (multi-leaf `AND`s, null
/// tests). The explicit cases pin the corners the random space rarely hits exactly: an empty chunk,
/// a no-match filter, an all-match filter, an all-null aggregate, a null in the filter column, a
/// `COUNT(*)` with no aggregate column, a floating-domain filter + aggregate (the double
/// accumulators), and the dict lane's own corners (`COUNT(*)` over codes, unsigned aggregate order,
/// `f32` extreme boxing, a one-leaf `AND` unwrapping to the lane).
class ComputeFilteredAggregateTest {

    private static final Arena ARENA = Arena.ofAuto();

    private static Stream<Integer> seeds() {
        return IntStream.range(0, 30).boxed();
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void fusedMatchesBruteForce(int seed) {
        // Given a random multi-column chunk and a random AND of 1-3 column-bound predicate leaves
        Random random = new Random(seed);
        int rows = random.nextInt(40);
        Reference f0 = randomLongReference(random, rows);
        Reference f1 = randomLongReference(random, rows);
        Reference agg = randomLongReference(random, rows);
        Map<String, Reference> references = Map.of("f0", f0, "f1", f1, "v", agg);

        Map<String, Array> columns = new LinkedHashMap<>();
        columns.put("f0", filterColumn(f0, random));
        columns.put("f1", filterColumn(f1, random));
        columns.put("v", longArray(agg, false));
        Chunk chunk = chunk(rows, columns);

        List<RowFilter> leaves = new ArrayList<>();
        int leafCount = 1 + random.nextInt(3);
        for (int k = 0; k < leafCount; k++) {
            leaves.add(new RowFilter.Column("f" + random.nextInt(2), randomPredicate(random)));
        }
        RowFilter filter = leaves.size() == 1
                ? leaves.getFirst()
                : RowFilter.and(leaves.toArray(RowFilter[]::new));

        // When the fused kernel folds the aggregate over the rows the filter selects
        FilteredAggregate result = Compute.filteredAggregate(chunk, filter, "v");

        // Then every field equals the brute-force reduction over the same rows
        assertMatchesBruteForce(result, rows, leaves, references, agg);
    }

    @Test
    void emptyChunkSelectsNothing() {
        // Given an empty chunk
        Reference empty = new Reference(new long[]{}, null);
        Chunk chunk = chunk(0, Map.of("f0", longArray(empty, false), "v", longArray(empty, false)));

        // When the kernel folds over zero rows
        FilteredAggregate result = Compute.filteredAggregate(chunk, RowFilter.gt("f0", 0L), "v");

        // Then nothing is selected and the sum is the additive identity, min/max are null
        assertThat(result.selectedRows()).isZero();
        assertThat(result.aggNonNullCount()).isZero();
        assertThat(result.sum()).isEqualTo(0L);
        assertThat(result.min()).isNull();
        assertThat(result.max()).isNull();
    }

    @Test
    void noMatchSelectsNothing() {
        // Given a predicate no row satisfies
        Reference f0 = new Reference(new long[]{1, 2, 3, 4}, null);
        Reference agg = new Reference(new long[]{10, 20, 30, 40}, null);
        Chunk chunk = chunk(4, Map.of("f0", longArray(f0, false), "v", longArray(agg, false)));

        // When the kernel filters with a never-true predicate
        FilteredAggregate result = Compute.filteredAggregate(chunk, RowFilter.gt("f0", 100L), "v");

        // Then no row is selected and the aggregate is empty
        assertThat(result.selectedRows()).isZero();
        assertThat(result.sum()).isEqualTo(0L);
        assertThat(result.min()).isNull();
        assertThat(result.max()).isNull();
    }

    @Test
    void allMatchFoldsEveryRow() {
        // Given a predicate every row satisfies and a fully valid aggregate
        Reference f0 = new Reference(new long[]{1, 2, 3, 4}, null);
        Reference agg = new Reference(new long[]{10, 20, 30, 40}, null);
        Chunk chunk = chunk(4, Map.of("f0", longArray(f0, false), "v", longArray(agg, false)));

        // When the kernel filters with an always-true predicate
        FilteredAggregate result = Compute.filteredAggregate(chunk, RowFilter.gt("f0", 0L), "v");

        // Then it folds every aggregate value
        assertThat(result.selectedRows()).isEqualTo(4L);
        assertThat(result.aggNonNullCount()).isEqualTo(4L);
        assertThat(result.sum()).isEqualTo(100L);
        assertThat(result.min()).isEqualTo(10L);
        assertThat(result.max()).isEqualTo(40L);
    }

    @Test
    void allNullAggregateContributesNothingButSelectsRows() {
        // Given selected rows whose aggregate values are all null
        Reference f0 = new Reference(new long[]{1, 2, 3, 4}, null);
        Reference agg = new Reference(new long[]{10, 20, 30, 40},
                new boolean[]{false, false, false, false});
        Chunk chunk = chunk(4, Map.of("f0", longArray(f0, false), "v", longArray(agg, true)));

        // When the kernel selects all rows but every aggregate value is null
        FilteredAggregate result = Compute.filteredAggregate(chunk, RowFilter.gt("f0", 0L), "v");

        // Then all rows are selected, none contribute to the aggregate, sum is the identity
        assertThat(result.selectedRows()).isEqualTo(4L);
        assertThat(result.aggNonNullCount()).isZero();
        assertThat(result.sum()).isEqualTo(0L);
        assertThat(result.min()).isNull();
        assertThat(result.max()).isNull();
    }

    @Test
    void nullInFilterColumnFailsThatLeaf() {
        // Given a filter column with a null where the value would otherwise match (three-valued logic)
        Reference f0 = new Reference(new long[]{10, 20, 30, 40},
                new boolean[]{true, false, true, true});
        Reference agg = new Reference(new long[]{1, 2, 3, 4}, null);
        Chunk chunk = chunk(4, Map.of("f0", longArray(f0, true), "v", longArray(agg, false)));

        // When the kernel filters f0 > 0 — the null row (index 1) is excluded though 20 > 0
        FilteredAggregate result = Compute.filteredAggregate(chunk, RowFilter.gt("f0", 0L), "v");

        // Then only the three non-null filter rows are selected
        assertThat(result.selectedRows()).isEqualTo(3L);
        assertThat(result.aggNonNullCount()).isEqualTo(3L);
        assertThat(result.sum()).isEqualTo(1L + 3L + 4L);
        assertThat(result.min()).isEqualTo(1L);
        assertThat(result.max()).isEqualTo(4L);
    }

    @Test
    void countStarFoldHasNoAggregate() {
        // Given a COUNT(*) fold — no aggregate column
        Reference f0 = new Reference(new long[]{1, 2, 3, 4, 5}, null);
        Chunk chunk = chunk(5, Map.of("f0", longArray(f0, false)));

        // When the kernel counts the selected rows with a null aggregate column
        FilteredAggregate result = Compute.filteredAggregate(chunk, RowFilter.gt("f0", 2L), null);

        // Then only the selected count is meaningful; the aggregate fields are empty
        assertThat(result.selectedRows()).isEqualTo(3L);
        assertThat(result.aggNonNullCount()).isZero();
        assertThat(result.sum()).isNull();
        assertThat(result.min()).isNull();
        assertThat(result.max()).isNull();
    }

    @Test
    void crossColumnAndIntersectsBothLeaves() {
        // Given a conjunction over two different columns: f0 > 1 AND f1 < 4
        Reference f0 = new Reference(new long[]{0, 2, 3, 5}, null);
        Reference f1 = new Reference(new long[]{9, 1, 3, 2}, null);
        Reference agg = new Reference(new long[]{10, 20, 30, 40}, null);
        Chunk chunk = chunk(4, Map.of(
                "f0", longArray(f0, false), "f1", longArray(f1, false), "v", longArray(agg, false)));
        RowFilter filter = RowFilter.gt("f0", 1L).and(RowFilter.lt("f1", 4L));

        // When the kernel intersects the two per-column leaves — rows 1,2,3 pass f0>1, rows 1,2,3 pass
        // f1<4, so rows 1,2,3 are selected (row 0 fails f0>1)
        FilteredAggregate result = Compute.filteredAggregate(chunk, filter, "v");

        // Then only the rows both leaves accept fold in
        assertThat(result.selectedRows()).isEqualTo(3L);
        assertThat(result.sum()).isEqualTo(20L + 30L + 40L);
        assertThat(result.min()).isEqualTo(20L);
        assertThat(result.max()).isEqualTo(40L);
    }

    @Test
    void floatingDomainFilterAndAggregate() {
        // Given a double filter column and a double aggregate — exercises the double accumulators
        double[] fValues = {0.5, 1.5, 2.5, 3.5};
        double[] aValues = {10.0, 20.0, 30.0, 40.0};
        Chunk chunk = chunk(4, Map.of(
                "f", doubleArray(fValues), "d", doubleArray(aValues)));

        // When the kernel filters f > 1.0 and folds the double aggregate
        FilteredAggregate result = Compute.filteredAggregate(chunk, RowFilter.gt("f", 1.0), "d");

        // Then only the last three rows fold, with a double sum/min/max
        assertThat(result.selectedRows()).isEqualTo(3L);
        assertThat(result.aggNonNullCount()).isEqualTo(3L);
        assertThat(result.sum()).isEqualTo(90.0);
        assertThat(result.min()).isEqualTo(20.0);
        assertThat(result.max()).isEqualTo(40.0);
    }

    @Test
    void dictFilterCountStarScansCodes() {
        // Given a dict-encoded filter column and no aggregate column — the dict lane's pure
        // vectorizable count loop, the COUNT(*) WHERE category = … push-down shape
        Chunk chunk = chunk(5, Map.of("f0", dictColumn(new long[]{5, 7}, new int[]{1, 0, 1, 1, 0})));

        // When the kernel counts the rows where the dict value is 7 (code 1)
        FilteredAggregate result = Compute.filteredAggregate(chunk, RowFilter.eq("f0", 7L), null);

        // Then only the selected count is meaningful; the aggregate fields are empty
        assertThat(result.selectedRows()).isEqualTo(3L);
        assertThat(result.aggNonNullCount()).isZero();
        assertThat(result.sum()).isNull();
        assertThat(result.min()).isNull();
        assertThat(result.max()).isNull();
    }

    @Test
    void dictFilterUnsignedAggregateKeepsUnsignedOrder() {
        // Given a dict-filtered u64 aggregate whose high-bit value (raw bits Long.MIN_VALUE = 2^63)
        // must order as the LARGEST magnitude — if the dict lane folded min/max with signed
        // comparison instead of the kernel's unsigned-aware order, 2^63 would come out as the min
        Array filter = dictColumn(new long[]{1, 2}, new int[]{1, 0, 1});
        MemorySegment seg = ARENA.allocate(24, 8);
        seg.setAtIndex(PTypeIO.LE_LONG, 0, Long.MIN_VALUE);
        seg.setAtIndex(PTypeIO.LE_LONG, 1, 7L);
        seg.setAtIndex(PTypeIO.LE_LONG, 2, 5L);
        Array agg = new MaterializedLongArray(DType.U64, 3, seg);
        Chunk chunk = chunk(3, Map.of("f0", filter, "v", agg));

        // When the kernel folds the u64 aggregate over the rows where the dict value is 2 (rows 0, 2)
        FilteredAggregate result = Compute.filteredAggregate(chunk, RowFilter.eq("f0", 2L), "v");

        // Then the extremes order unsigned (5 < 2^63) and the sum wraps on the raw bits
        assertThat(result.selectedRows()).isEqualTo(2L);
        assertThat(result.aggNonNullCount()).isEqualTo(2L);
        assertThat(result.sum()).isEqualTo(Long.MIN_VALUE + 5L);
        assertThat(result.min()).isEqualTo(5L);
        assertThat(result.max()).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void dictFilterFloatAggregateBoxesFloatExtremes() {
        // Given a dict-filtered f32 aggregate — the dict lane must keep the kernel's double-lane
        // boxing contract (Double sum, Float extremes), not silently promote the extremes to Double
        Array filter = dictColumn(new long[]{1, 2}, new int[]{1, 0, 1, 1});
        Chunk chunk = chunk(4, Map.of("f0", filter, "v", floatArray(1.5f, 9.0f, 2.5f, 4.0f)));

        // When the kernel folds the f32 aggregate over the rows where the dict value is 2 (rows 0, 2, 3)
        FilteredAggregate result = Compute.filteredAggregate(chunk, RowFilter.eq("f0", 2L), "v");

        // Then the sum is a Double and the extremes carry the column's Float box
        assertThat(result.selectedRows()).isEqualTo(3L);
        assertThat(result.aggNonNullCount()).isEqualTo(3L);
        assertThat(result.sum()).isInstanceOf(Double.class).isEqualTo(8.0);
        assertThat(result.min()).isInstanceOf(Float.class).isEqualTo(1.5f);
        assertThat(result.max()).isInstanceOf(Float.class).isEqualTo(4.0f);
    }

    @Test
    void andOfOneDictLeafUnwrapsToTheLane() {
        // Given a one-element AND around a dict comparison leaf — RowFilter.and(...) wraps a single
        // conjunct, and the dict lane must see through it to the leaf rather than falling back
        Chunk chunk = chunk(5, Map.of(
                "f0", dictColumn(new long[]{5, 7}, new int[]{1, 0, 1, 1, 0}),
                "v", longArray(new Reference(new long[]{1, 2, 4, 8, 16}, null), false)));

        // When the kernel folds through the one-leaf AND
        FilteredAggregate result = Compute.filteredAggregate(chunk,
                new RowFilter.And(List.of(RowFilter.eq("f0", 7L))), "v");

        // Then the selected rows 0, 2 and 3 fold exactly as with the bare leaf
        assertThat(result.selectedRows()).isEqualTo(3L);
        assertThat(result.sum()).isEqualTo(1L + 4L + 8L);
        assertThat(result.min()).isEqualTo(1L);
        assertThat(result.max()).isEqualTo(8L);
    }

    @Test
    void f16AggregateSumsAsDouble() {
        // Given a double filter column and an f16 aggregate — f16 has no specialized long/double lane,
        // so it exercises the generic boxing fold (a bug previously forced its SUM to null). The values
        // 10/20/30/40 are exactly representable in IEEE 754 half precision, so no rounding clouds the
        // brute-force reference.
        double[] fValues = {0.5, 1.5, 2.5, 3.5};
        float[] aValues = {10.0f, 20.0f, 30.0f, 40.0f};
        Chunk chunk = chunk(4, Map.of("f", doubleArray(fValues), "h", float16Array(aValues)));

        // When the kernel filters f > 1.0 and folds the f16 aggregate over the last three rows
        FilteredAggregate result = Compute.filteredAggregate(chunk, RowFilter.gt("f", 1.0), "h");

        // Then SUM is a non-null Double (mirroring the two-pass Reductions.sum f16 routing), and
        // MIN / MAX are the f16-widened Float extremes
        assertThat(result.selectedRows()).isEqualTo(3L);
        assertThat(result.aggNonNullCount()).isEqualTo(3L);
        assertThat(result.sum()).isInstanceOf(Double.class).isEqualTo(90.0);
        assertThat(result.min()).isEqualTo(20.0f);
        assertThat(result.max()).isEqualTo(40.0f);
    }

    private static void assertMatchesBruteForce(FilteredAggregate result, int rows, List<RowFilter> leaves,
                                                Map<String, Reference> references, Reference agg) {
        long selected = 0;
        long nonNull = 0;
        long sum = 0;
        long min = 0;
        long max = 0;
        boolean found = false;
        for (int i = 0; i < rows; i++) {
            if (!allLeavesAccept(leaves, references, i)) {
                continue;
            }
            selected++;
            if (agg.isNull(i)) {
                continue;
            }
            nonNull++;
            long v = agg.values()[i];
            sum += v;
            if (!found) {
                min = v;
                max = v;
                found = true;
            } else {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        assertThat(result.selectedRows()).as("selectedRows").isEqualTo(selected);
        assertThat(result.aggNonNullCount()).as("aggNonNullCount").isEqualTo(nonNull);
        assertThat(result.sum()).as("sum").isEqualTo(sum);
        assertThat(result.min()).as("min").isEqualTo(found ? Long.valueOf(min) : null);
        assertThat(result.max()).as("max").isEqualTo(found ? Long.valueOf(max) : null);
    }

    private static boolean allLeavesAccept(List<RowFilter> leaves, Map<String, Reference> references, int i) {
        for (RowFilter leaf : leaves) {
            RowFilter.Column column = (RowFilter.Column) leaf;
            if (!accepts(column.predicate(), references.get(column.column()), i)) {
                return false;
            }
        }
        return true;
    }

    /// The independent reference evaluation of one predicate against one column at row `i`, written
    /// straight from the three-valued-logic semantics (a null fails every value leaf, satisfies only
    /// the null test).
    private static boolean accepts(Predicate predicate, Reference column, int i) {
        boolean isNull = column.isNull(i);
        long v = column.values()[i];
        return switch (predicate) {
            case Predicate.IsNull ignored -> isNull;
            case Predicate.IsNotNull ignored -> !isNull;
            case Predicate.Eq eq -> !isNull && v == ((Number) eq.value()).longValue();
            case Predicate.Neq neq -> !isNull && v != ((Number) neq.value()).longValue();
            case Predicate.Lt lt -> !isNull && v < ((Number) lt.value()).longValue();
            case Predicate.Gt gt -> !isNull && v > ((Number) gt.value()).longValue();
            case Predicate.Lte lte -> !isNull && v <= ((Number) lte.value()).longValue();
            case Predicate.Gte gte -> !isNull && v >= ((Number) gte.value()).longValue();
            case Predicate.Between between -> !isNull
                    && v >= ((Number) between.lo()).longValue() && v <= ((Number) between.hi()).longValue();
            default -> throw new IllegalStateException("unexpected predicate: " + predicate);
        };
    }

    private static Predicate randomPredicate(Random random) {
        long c = random.nextInt(35) - 12;
        return switch (random.nextInt(9)) {
            case 0 -> new Predicate.Eq(c);
            case 1 -> new Predicate.Neq(c);
            case 2 -> new Predicate.Lt(c);
            case 3 -> new Predicate.Gt(c);
            case 4 -> new Predicate.Lte(c);
            case 5 -> new Predicate.Gte(c);
            case 6 -> new Predicate.Between(c, c + random.nextInt(10));
            case 7 -> new Predicate.IsNull();
            default -> new Predicate.IsNotNull();
        };
    }

    private static Reference randomLongReference(Random random, int n) {
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            values[i] = random.nextInt(31) - 10;
        }
        boolean[] valid = null;
        if (random.nextBoolean()) {
            valid = new boolean[n];
            for (int i = 0; i < n; i++) {
                valid[i] = random.nextInt(4) != 0;
            }
        }
        return new Reference(values, valid);
    }

    /// Materializes a filter reference as a plain long-domain array or, half the time, as a
    /// dict-encoded view of the same values — driving the dict code-scan lane through the same
    /// brute-force oracle.
    private static Array filterColumn(Reference reference, Random random) {
        if (random.nextBoolean()) {
            return longArray(reference, random.nextBoolean());
        }
        return dictArray(reference, random);
    }

    /// Encodes the reference values dictionary-style: a pool of the distinct values indexed by
    /// per-row `u8` codes, randomly flat or split into a two-child [ChunkedByteArray] (the
    /// shared-dictionary layout the scan iterator produces), masked when the reference carries
    /// validity.
    private static Array dictArray(Reference reference, Random random) {
        long[] values = reference.values();
        Map<Long, Integer> codeOf = new HashMap<>();
        List<Long> pool = new ArrayList<>();
        int[] codes = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            codes[i] = codeOf.computeIfAbsent(values[i], value -> {
                pool.add(value);
                return pool.size() - 1;
            });
        }
        MemorySegment poolSeg = ARENA.allocate(Math.max(8L, pool.size() * 8L), 8);
        for (int i = 0; i < pool.size(); i++) {
            poolSeg.setAtIndex(PTypeIO.LE_LONG, i, pool.get(i));
        }
        MaterializedLongArray poolArray = new MaterializedLongArray(DType.I64, pool.size(), poolSeg);
        ByteArray codesArray;
        if (codes.length < 2 || random.nextBoolean()) {
            codesArray = byteArray(codes, 0, codes.length);
        } else {
            int cut = 1 + random.nextInt(codes.length - 1);
            codesArray = ChunkedByteArray.of(DType.U8, codes.length,
                    List.of(byteArray(codes, 0, cut), byteArray(codes, cut, codes.length)));
        }
        Array dict = DictLongArray.of(DType.I64, values.length, poolArray, codesArray);
        if (reference.valid() == null) {
            return dict;
        }
        return new MaskedArray(dict, boolArray(reference.valid()));
    }

    /// Builds a flat-coded dict column for the pinned dict-lane cases.
    private static Array dictColumn(long[] pool, int[] codes) {
        MemorySegment poolSeg = ARENA.allocate(Math.max(8L, pool.length * 8L), 8);
        for (int i = 0; i < pool.length; i++) {
            poolSeg.setAtIndex(PTypeIO.LE_LONG, i, pool[i]);
        }
        MaterializedLongArray poolArray = new MaterializedLongArray(DType.I64, pool.length, poolSeg);
        return DictLongArray.of(DType.I64, codes.length, poolArray, byteArray(codes, 0, codes.length));
    }

    private static MaterializedByteArray byteArray(int[] codes, int from, int to) {
        MemorySegment segment = ARENA.allocate(Math.max(1L, (long) to - from));
        for (int i = from; i < to; i++) {
            segment.set(ValueLayout.JAVA_BYTE, (long) i - from, (byte) codes[i]);
        }
        return new MaterializedByteArray(DType.U8, (long) to - from, segment);
    }

    private static Array floatArray(float... values) {
        MemorySegment segment = ARENA.allocate(Math.max(4L, values.length * 4L), 4);
        for (int i = 0; i < values.length; i++) {
            segment.setAtIndex(PTypeIO.LE_FLOAT, i, values[i]);
        }
        return new MaterializedFloatArray(DType.F32, values.length, segment);
    }

    private static Chunk chunk(long rows, Map<String, Array> columns) {
        Map<String, DType> dtypes = new LinkedHashMap<>();
        columns.forEach((name, array) -> dtypes.put(name, array.dtype()));
        return new Chunk(rows, columns, dtypes, ARENA, c -> { /* no-op: the shared arena outlives the chunk */ });
    }

    /// Builds a long-domain column from a reference, optionally narrowing it to an `i32` array, and
    /// wrapping it in a [MaskedArray] when the reference carries a validity bitmap (or when `nullable`
    /// forces an all-valid mask, to exercise the masked path even with no nulls).
    private static Array longArray(Reference reference, boolean nullable) {
        long[] values = reference.values();
        Array data;
        if (values.length % 2 == 0) {
            MemorySegment segment = ARENA.allocate(Math.max(8L, values.length * 8L), 8);
            for (int i = 0; i < values.length; i++) {
                segment.setAtIndex(PTypeIO.LE_LONG, i, values[i]);
            }
            data = new MaterializedLongArray(DType.I64, values.length, segment);
        } else {
            MemorySegment segment = ARENA.allocate(Math.max(4L, values.length * 4L), 4);
            for (int i = 0; i < values.length; i++) {
                segment.setAtIndex(PTypeIO.LE_INT, i, (int) values[i]);
            }
            data = new MaterializedIntArray(DType.I32, values.length, segment);
        }
        if (reference.valid() == null && !nullable) {
            return data;
        }
        boolean[] valid = reference.valid() != null ? reference.valid() : allValid(values.length);
        return new MaskedArray(data, boolArray(valid));
    }

    private static Array doubleArray(double[] values) {
        MemorySegment segment = ARENA.allocate(Math.max(8L, values.length * 8L), 8);
        for (int i = 0; i < values.length; i++) {
            segment.setAtIndex(PTypeIO.LE_DOUBLE, i, values[i]);
        }
        return new MaterializedDoubleArray(DType.F64, values.length, segment);
    }

    private static Array float16Array(float[] values) {
        MemorySegment segment = ARENA.allocate(Math.max(2L, values.length * 2L), 2);
        for (int i = 0; i < values.length; i++) {
            segment.setAtIndex(PTypeIO.LE_SHORT, i, Float.floatToFloat16(values[i]));
        }
        return new MaterializedFloat16Array(DType.F16, values.length, segment);
    }

    private static boolean[] allValid(int n) {
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            valid[i] = true;
        }
        return valid;
    }

    private static BoolArray boolArray(boolean[] values) {
        long bytes = Math.max(1L, (values.length + 7) >>> 3);
        MemorySegment segment = ARENA.allocate(bytes);
        for (int i = 0; i < values.length; i++) {
            if (values[i]) {
                long byteIndex = i >>> 3;
                int existing = segment.get(ValueLayout.JAVA_BYTE, byteIndex) & 0xff;
                segment.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) (existing | (1 << (i & 7))));
            }
        }
        return new MaterializedBoolArray(DType.BOOL, values.length, segment);
    }

    /// The brute-force reference column: raw values plus an optional validity bitmap (`null` when
    /// every row is valid).
    ///
    /// @param values the raw row values
    /// @param valid  the validity flags, or `null` when every row is valid
    private record Reference(long[] values, boolean[] valid) {

        boolean isNull(int i) {
            return valid != null && !valid[i];
        }
    }
}
