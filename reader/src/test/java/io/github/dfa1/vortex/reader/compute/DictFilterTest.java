package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.ChunkedByteArray;
import io.github.dfa1.vortex.reader.array.DictDoubleArray;
import io.github.dfa1.vortex.reader.array.DictFloatArray;
import io.github.dfa1.vortex.reader.array.DictIntArray;
import io.github.dfa1.vortex.reader.array.DictLongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;
import io.github.dfa1.vortex.reader.array.OffsetByteArray;
import io.github.dfa1.vortex.reader.array.OffsetLongArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Oracle test for the [DictFilter] code-scan lane of [Compute#filteredSum(Array, Predicate,
/// Array)]: the lane result must be bit-identical to the independent boxing reference — a plain
/// per-element `if (predicate) acc += value` loop over the same columns — which shares no code with
/// the code-scan (the reference decodes every row through the dict accessor; the lane never does).
///
/// The randomized cases sweep every dict value domain (`i64` / `u64` / `i32` / `f64` / `f32`),
/// every codes structure the lane specializes (flat, chunked, chunked behind an offset slice — the
/// shared-dictionary shape the scan iterator produces), filter nullability, and every comparison
/// predicate. The explicit cases pin the lane's own corners: the single-match compare loop, the
/// zero-match early return, the accessor fallback for a segmentless codes child, the broadcast
/// (undersized) codes buffer, and the `u16`-codes decline that must stay on the generic path.
class DictFilterTest {

    private static final Arena ARENA = Arena.ofAuto();

    private static Stream<Integer> seeds() {
        return IntStream.range(0, 30).boxed();
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void dictLaneMatchesBoxingReference(int seed) {
        // Given a random dict-encoded filter column, aggregate column and comparison predicate
        Random random = new Random(seed);
        Array filter = randomDictColumn(random, random.nextInt(120));
        // The offset-slice shape shortens the view, so the aggregate aligns to the filter's length.
        Array agg = randomAggColumn(random, (int) filter.length());
        for (Predicate predicate : everyComparison(random, filter)) {
            // When the code-scan lane and the boxing reference both run
            // Then they agree exactly
            assertFusedMatchesReference(filter, predicate, agg);
        }
    }

    @Test
    void singleMatchScansChunkedCodesBehindOffsetSlice() {
        // Given the benchmark shape: a shared dict spanning two code chunks, viewed through an
        // offset slice selecting the second half, under an equality predicate (one matching code)
        long[] pool = {10, 20, 30, 40};
        int[] first = {0, 1, 2, 3, 0, 1};
        int[] second = {3, 3, 0, 3, 1, 3};
        DictLongArray dict = dictWithChunkedCodes(pool, first, second);
        Array filter = new OffsetLongArray(DType.I64, second.length, dict, first.length);
        Array agg = longAgg(1, 2, 4, 8, 16, 32);

        // When the fused kernel sums over `value == 40` (code 3) in the sliced view
        Number result = Compute.filteredSum(filter, new Predicate.Eq(40L), agg);

        // Then rows 0, 1, 3 and 5 of the slice contribute
        assertThat(result).isEqualTo(1L + 2L + 8L + 32L);
        assertFusedMatchesReference(filter, new Predicate.Eq(40L), agg);
    }

    @Test
    void zeroMatchingPoolValuesSumToIdentityWithoutScanning() {
        // Given a predicate no pool value satisfies
        Array filter = flatDict(new long[]{1, 2, 3}, new int[]{0, 1, 2, 1, 0});
        Array agg = longAgg(10, 20, 30, 40, 50);

        // When the fused kernel filters with a never-true predicate
        Number result = Compute.filteredSum(filter, new Predicate.Gt(100L), agg);

        // Then the sum is the additive identity
        assertThat(result).isEqualTo(0L);
        assertFusedMatchesReference(filter, new Predicate.Gt(100L), agg);
    }

    @Test
    void everyPoolValueMatchingSumsAllRows() {
        // Given a predicate every pool value satisfies (the multi-match table loop)
        Array filter = flatDict(new long[]{1, 2, 3}, new int[]{0, 1, 2, 1, 0});
        Array agg = longAgg(10, 20, 30, 40, 50);

        // When the fused kernel filters with an always-true predicate
        Number result = Compute.filteredSum(filter, new Predicate.Gt(0L), agg);

        // Then it totals every aggregate value
        assertThat(result).isEqualTo(150L);
        assertFusedMatchesReference(filter, new Predicate.Gt(0L), agg);
    }

    @Test
    void segmentlessCodesChildFallsBackToAccessorRun() {
        // Given chunked codes whose second child is an offset view (no backing segment), so its run
        // must scan through the accessor while the first child's run scans its segment
        long[] pool = {5, 6, 7};
        ByteArray flat = byteCodes(0, 1, 2, 1);
        ByteArray sliced = new OffsetByteArray(DType.U8, 3, byteCodes(9, 2, 0, 2), 1);
        ChunkedByteArray codes = ChunkedByteArray.of(DType.U8, 7, List.of(flat, sliced));
        Array filter = DictLongArray.of(DType.I64, 7, longPool(pool), codes);
        Array agg = longAgg(1, 2, 4, 8, 16, 32, 64);

        // When the fused kernel sums over `value == 7` (code 2)
        Number result = Compute.filteredSum(filter, new Predicate.Eq(7L), agg);

        // Then code-2 rows from both the segment run and the accessor run contribute
        assertThat(result).isEqualTo(4L + 16L + 64L);
        assertFusedMatchesReference(filter, new Predicate.Eq(7L), agg);
    }

    @Test
    void broadcastCodesBufferFallsBackToAccessorRun() {
        // Given a codes buffer physically holding one element broadcast over five logical rows
        // (a ConstantEncoding shape), which the segment run must decline — its capacity is smaller
        // than the run — leaving the accessor to apply the wrap
        MemorySegment one = ARENA.allocate(1);
        one.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
        ByteArray broadcast = new MaterializedByteArray(DType.U8, 5, one);
        Array filter = DictLongArray.of(DType.I64, 5, longPool(new long[]{10, 20}), broadcast);
        Array agg = longAgg(1, 2, 4, 8, 16);

        // When the fused kernel sums over `value == 20` (the broadcast code 1)
        Number result = Compute.filteredSum(filter, new Predicate.Eq(20L), agg);

        // Then every row matches through the wrapped accessor reads
        assertThat(result).isEqualTo(31L);
        assertFusedMatchesReference(filter, new Predicate.Eq(20L), agg);
    }

    @Test
    void nullFilterRowsAreNeverSelected() {
        // Given a dict filter behind a validity mask that nulls two of the matching rows
        Array dict = flatDict(new long[]{1, 2}, new int[]{1, 1, 0, 1, 1});
        Array filter = new MaskedArray(dict, ComputeArrays.boolArray(ARENA, true, false, true, true, false));
        Array agg = longAgg(1, 2, 4, 8, 16);

        // When the fused kernel sums over `value == 2` under three-valued logic
        Number result = Compute.filteredSum(filter, new Predicate.Eq(2L), agg);

        // Then only the valid matching rows 0 and 3 contribute
        assertThat(result).isEqualTo(9L);
        assertFusedMatchesReference(filter, new Predicate.Eq(2L), agg);
    }

    @Test
    void nullAggregateRowsContributeNothing() {
        // Given an aggregate with nulls on two of the rows the dict filter selects
        Array filter = flatDict(new long[]{1, 2}, new int[]{1, 1, 0, 1, 1});
        Array agg = new MaskedArray(longAgg(1, 2, 4, 8, 16),
                ComputeArrays.boolArray(ARENA, false, true, true, true, false));

        // When the fused kernel sums over `value == 2`
        Number result = Compute.filteredSum(filter, new Predicate.Eq(2L), agg);

        // Then only the non-null selected rows 1 and 3 contribute
        assertThat(result).isEqualTo(10L);
        assertFusedMatchesReference(filter, new Predicate.Eq(2L), agg);
    }

    @Test
    void wideCodesDeclineTheLaneButStayCorrect() {
        // Given a dict whose codes are u16 (a ShortArray), which the u8 code-scan lane must decline
        MemorySegment seg = ARENA.allocate(10, 2);
        int[] codes = {0, 1, 2, 1, 0};
        for (int i = 0; i < codes.length; i++) {
            seg.setAtIndex(PTypeIO.LE_SHORT, i, (short) codes[i]);
        }
        MaterializedShortArray wide = new MaterializedShortArray(DType.U16, codes.length, seg);
        Array filter = DictLongArray.of(DType.I64, codes.length, longPool(new long[]{7, 8, 9}), wide);
        Array agg = longAgg(1, 2, 4, 8, 16);

        // When the fused kernel sums over `value == 8` (code 1)
        Number result = Compute.filteredSum(filter, new Predicate.Eq(8L), agg);

        // Then the generic path still selects rows 1 and 3
        assertThat(result).isEqualTo(10L);
        assertFusedMatchesReference(filter, new Predicate.Eq(8L), agg);
    }

    @Test
    void nanAndSignedZeroPoolValuesOrderLikeDoubleCompare() {
        // Given a double dict pool holding the Double.compare corner values — NaN, -0.0 and 0.0.
        // The lane evaluates the predicate on the POOL while the reference evaluates it per ROW;
        // if the lane ever lowered through primitive `==`/`<` instead of PrimitiveFilter's
        // Double.compare order (where -0.0 < 0.0 and NaN is greater than everything), the match
        // table would silently disagree with the reference on exactly these values.
        double[] pool = {Double.NaN, -0.0, 0.0, 42.0};
        int[] codes = {0, 1, 2, 3, 1, 2, 0};
        Array filter = flatDoubleDict(pool, codes);
        Array agg = longAgg(1, 2, 4, 8, 16, 32, 64);
        Predicate[] cornerPredicates = {
                new Predicate.Eq(0.0),      // must not match -0.0
                new Predicate.Neq(0.0),     // must match -0.0 and NaN
                new Predicate.Lt(0.0),      // matches -0.0 only, not NaN
                new Predicate.Gt(41.0),     // matches 42.0 AND NaN (NaN orders above everything)
                new Predicate.Between(-0.0, 0.0)
        };
        for (Predicate predicate : cornerPredicates) {
            // When the code-scan lane and the boxing reference both run
            // Then they agree exactly on the Double.compare ordering
            assertFusedMatchesReference(filter, predicate, agg);
        }
    }

    @Test
    void emptyDictSumsToIdentity() {
        // Given an empty dict filter and aggregate
        Array filter = flatDict(new long[]{1}, new int[]{});
        Array agg = longAgg();

        // When the fused kernel filters and sums nothing
        Number result = Compute.filteredSum(filter, new Predicate.Gt(0L), agg);

        // Then it is the additive identity
        assertThat(result).isEqualTo(0L);
        assertFusedMatchesReference(filter, new Predicate.Gt(0L), agg);
    }

    private static void assertFusedMatchesReference(Array filter, Predicate predicate, Array agg) {
        Number reference = referenceFilteredSum(filter, predicate, agg);
        Number fused = Compute.filteredSum(filter, predicate, agg);
        assertThat(fused)
                .as("fused %s on filter %s, agg %s", predicate, filter.dtype(), agg.dtype())
                .isEqualTo(reference);
    }

    /// Independent boxing oracle: a plain per-element filter-then-sum over the same columns, with
    /// every row decoded through the dict accessor — the exact path the code-scan lane bypasses.
    private static Number referenceFilteredSum(Array filter, Predicate predicate, Array agg) {
        long n = filter.length();
        boolean floating = agg.dtype() instanceof DType.Primitive prim && prim.ptype().isFloating();
        if (floating) {
            double acc = 0.0;
            for (long i = 0; i < n; i++) {
                if (PredicateEvaluator.evaluate(filter, i, predicate) && !Values.isNullAt(agg, i)) {
                    acc += ((Number) Values.valueAt(agg, i)).doubleValue();
                }
            }
            return acc;
        }
        long acc = 0L;
        for (long i = 0; i < n; i++) {
            if (PredicateEvaluator.evaluate(filter, i, predicate) && !Values.isNullAt(agg, i)) {
                acc += ((Number) Values.valueAt(agg, i)).longValue();
            }
        }
        return acc;
    }

    /// Builds a random dict filter column: a pool of 1–16 values in a random domain, random valid
    /// codes in a random structure (flat, chunked, or chunked behind an offset slice), optionally
    /// masked with random validity.
    private static Array randomDictColumn(Random random, int n) {
        int poolSize = 1 + random.nextInt(16);
        int[] codes = new int[n];
        for (int i = 0; i < n; i++) {
            codes[i] = random.nextInt(poolSize);
        }
        Array dict = switch (random.nextInt(5)) {
            case 0 -> dictOfLongs(random, DType.I64, poolSize, codes);
            case 1 -> dictOfLongs(random, DType.U64, poolSize, codes);
            case 2 -> dictOfInts(random, poolSize, codes);
            case 3 -> dictOfDoubles(random, poolSize, codes);
            default -> dictOfFloats(random, poolSize, codes);
        };
        if (random.nextInt(3) == 0) {
            // The offset-slice shape shortens the view, so validity aligns to the dict's length.
            int len = (int) dict.length();
            boolean[] valid = new boolean[len];
            for (int i = 0; i < len; i++) {
                valid[i] = random.nextInt(4) != 0;
            }
            return new MaskedArray(dict, ComputeArrays.boolArray(ARENA, valid));
        }
        return dict;
    }

    private static Array dictOfLongs(Random random, DType.Primitive dtype, int poolSize, int[] codes) {
        long[] pool = new long[poolSize];
        for (int i = 0; i < poolSize; i++) {
            pool[i] = random.nextInt(101) - 50;
        }
        MemorySegment seg = ARENA.allocate(Math.max(8L, poolSize * 8L), 8);
        for (int i = 0; i < poolSize; i++) {
            seg.setAtIndex(PTypeIO.LE_LONG, i, pool[i]);
        }
        MaterializedLongArray values = new MaterializedLongArray(dtype, poolSize, seg);
        ByteArray codeArray = randomCodesStructure(random, codes);
        DictLongArray dict = DictLongArray.of(dtype, codes.length, values, codeArray);
        if (random.nextBoolean() && codes.length > 1) {
            // Exercise the offset-slice shape: view the tail of a longer dict.
            int cut = 1 + random.nextInt(codes.length - 1);
            return new OffsetLongArray(dtype, codes.length - cut, dict, cut);
        }
        return dict;
    }

    private static Array dictOfInts(Random random, int poolSize, int[] codes) {
        MemorySegment seg = ARENA.allocate(Math.max(4L, poolSize * 4L), 4);
        for (int i = 0; i < poolSize; i++) {
            seg.setAtIndex(PTypeIO.LE_INT, i, random.nextInt(101) - 50);
        }
        MaterializedIntArray values = new MaterializedIntArray(DType.I32, poolSize, seg);
        return DictIntArray.of(DType.I32, codes.length, values, randomCodesStructure(random, codes));
    }

    private static Array dictOfDoubles(Random random, int poolSize, int[] codes) {
        MemorySegment seg = ARENA.allocate(Math.max(8L, poolSize * 8L), 8);
        for (int i = 0; i < poolSize; i++) {
            seg.setAtIndex(PTypeIO.LE_DOUBLE, i, random.nextInt(101) - 50);
        }
        MaterializedDoubleArray values = new MaterializedDoubleArray(DType.F64, poolSize, seg);
        return DictDoubleArray.of(DType.F64, codes.length, values, randomCodesStructure(random, codes));
    }

    private static Array dictOfFloats(Random random, int poolSize, int[] codes) {
        MemorySegment seg = ARENA.allocate(Math.max(4L, poolSize * 4L), 4);
        for (int i = 0; i < poolSize; i++) {
            seg.setAtIndex(PTypeIO.LE_FLOAT, i, random.nextInt(101) - 50);
        }
        MaterializedFloatArray values = new MaterializedFloatArray(DType.F32, poolSize, seg);
        return DictFloatArray.of(DType.F32, codes.length, values, randomCodesStructure(random, codes));
    }

    /// Wraps the codes as a flat byte array or a 2–3-child [ChunkedByteArray], the two structures
    /// the run walk specializes.
    private static ByteArray randomCodesStructure(Random random, int[] codes) {
        if (codes.length < 4 || random.nextBoolean()) {
            return byteCodes(codes);
        }
        int firstCut = 1 + random.nextInt(codes.length - 2);
        int secondCut = firstCut + 1 + random.nextInt(codes.length - firstCut - 1);
        List<ByteArray> children = List.of(
                byteCodes(slice(codes, 0, firstCut)),
                byteCodes(slice(codes, firstCut, secondCut)),
                byteCodes(slice(codes, secondCut, codes.length)));
        return ChunkedByteArray.of(DType.U8, codes.length, children);
    }

    private static int[] slice(int[] codes, int from, int to) {
        int[] out = new int[to - from];
        System.arraycopy(codes, from, out, 0, out.length);
        return out;
    }

    private static Array randomAggColumn(Random random, int n) {
        boolean[] valid = random.nextInt(3) == 0 ? new boolean[n] : null;
        if (valid != null) {
            for (int i = 0; i < n; i++) {
                valid[i] = random.nextInt(4) != 0;
            }
        }
        Array agg;
        if (random.nextBoolean()) {
            long[] values = new long[n];
            for (int i = 0; i < n; i++) {
                values[i] = random.nextInt(1001) - 500;
            }
            agg = longAgg(values);
        } else {
            MemorySegment seg = ARENA.allocate(Math.max(8L, n * 8L), 8);
            for (int i = 0; i < n; i++) {
                seg.setAtIndex(PTypeIO.LE_DOUBLE, i, random.nextInt(1001) - 500);
            }
            agg = new MaterializedDoubleArray(DType.F64, n, seg);
        }
        return valid == null ? agg : new MaskedArray(agg, ComputeArrays.boolArray(ARENA, valid));
    }

    private static Predicate[] everyComparison(Random random, Array filter) {
        Array base = filter instanceof MaskedArray masked ? masked.inner() : filter;
        boolean floating = base.dtype() instanceof DType.Primitive prim && prim.ptype().isFloating();
        Comparable<?> a = constant(random, floating);
        Comparable<?> b = constant(random, floating);
        Comparable<?> lo = compare(a, b) <= 0 ? a : b;
        Comparable<?> hi = compare(a, b) <= 0 ? b : a;
        return new Predicate[]{
                new Predicate.Eq((Object) a),
                new Predicate.Neq((Object) a),
                new Predicate.Lt(a),
                new Predicate.Gt(a),
                new Predicate.Lte(a),
                new Predicate.Gte(a),
                new Predicate.Between(lo, hi)
        };
    }

    @SuppressWarnings("unchecked")
    private static int compare(Comparable<?> a, Comparable<?> b) {
        return ((Comparable<Object>) a).compareTo(b);
    }

    private static Comparable<?> constant(Random random, boolean floating) {
        if (floating) {
            return (double) (random.nextInt(101) - 50);
        }
        return (long) (random.nextInt(101) - 50);
    }

    private static DictLongArray flatDict(long[] pool, int[] codes) {
        return DictLongArray.of(DType.I64, codes.length, longPool(pool), byteCodes(codes));
    }

    private static DictDoubleArray flatDoubleDict(double[] pool, int[] codes) {
        MemorySegment seg = ARENA.allocate(Math.max(8L, pool.length * 8L), 8);
        for (int i = 0; i < pool.length; i++) {
            seg.setAtIndex(PTypeIO.LE_DOUBLE, i, pool[i]);
        }
        MaterializedDoubleArray values = new MaterializedDoubleArray(DType.F64, pool.length, seg);
        return DictDoubleArray.of(DType.F64, codes.length, values, byteCodes(codes));
    }

    private static DictLongArray dictWithChunkedCodes(long[] pool, int[] first, int[] second) {
        ChunkedByteArray codes = ChunkedByteArray.of(DType.U8, (long) first.length + second.length,
                List.of(byteCodes(first), byteCodes(second)));
        return DictLongArray.of(DType.I64, first.length + second.length, longPool(pool), codes);
    }

    private static MaterializedLongArray longPool(long[] pool) {
        MemorySegment seg = ARENA.allocate(Math.max(8L, pool.length * 8L), 8);
        for (int i = 0; i < pool.length; i++) {
            seg.setAtIndex(PTypeIO.LE_LONG, i, pool[i]);
        }
        return new MaterializedLongArray(DType.I64, pool.length, seg);
    }

    private static MaterializedLongArray longAgg(long... values) {
        MemorySegment seg = ARENA.allocate(Math.max(8L, values.length * 8L), 8);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(PTypeIO.LE_LONG, i, values[i]);
        }
        return new MaterializedLongArray(DType.I64, values.length, seg);
    }

    private static MaterializedByteArray byteCodes(int... codes) {
        MemorySegment seg = ARENA.allocate(Math.max(1, codes.length));
        for (int i = 0; i < codes.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, (byte) codes[i]);
        }
        return new MaterializedByteArray(DType.U8, codes.length, seg);
    }
}
