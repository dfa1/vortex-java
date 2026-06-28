package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Oracle test for the fused [Compute#filteredSum(Array, Predicate, Array)] kernel: the one-pass
/// fused result must be bit-identical to the two-pass reference it replaces — a
/// [Compute#filter(Array, Predicate, Arena)] into a [Mask] followed by a [Compute#sum(Array, Mask)]
/// over that mask. The two paths share no code on the hot lane, so any divergence between fusion and
/// the materialized-mask pipeline is a correctness bug.
///
/// The randomized cases pair a random primitive filter column (`i64` / `i32` / `f64`, with and
/// without nulls) against a random primitive aggregate column, under each comparison predicate, so
/// every filter/aggregate domain crossing (long/double, hot lane and the narrow-int generic
/// fall-back) is exercised. The explicit cases pin the corners — empty, an all-null filter, an
/// all-null aggregate, zero matches, and a full match.
class FusedFilterSumTest {

    private static final Arena ARENA = Arena.ofAuto();

    private static Stream<Integer> seeds() {
        return IntStream.range(0, 30).boxed();
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void fusedMatchesTwoPass(int seed) {
        // Given a random filter column, aggregate column and comparison predicate for this seed
        Random random = new Random(seed);
        Array filter = randomColumn(random, random.nextBoolean());
        // The aggregate must align positionally with the filter, so build it at the filter length.
        Array agg = randomColumn(random, random.nextBoolean(), (int) filter.length());
        for (Predicate predicate : everyComparison(random, filter)) {
            // When the fused kernel and the two-pass reference both run
            // Then they agree exactly
            assertFusedMatchesTwoPass(filter, predicate, agg);
        }
    }

    @Test
    void emptyColumnsSumToIdentity() {
        // Given two empty columns
        Array filter = longColumn(new long[]{}, null);
        Array agg = longColumn(new long[]{}, null);

        // When the fused kernel filters and sums nothing
        Number result = Compute.filteredSum(filter, new Predicate.Gt(0L), agg);

        // Then it is the additive identity, matching the two-pass path
        assertThat(result).isEqualTo(0L);
        assertFusedMatchesTwoPass(filter, new Predicate.Gt(0L), agg);
    }

    @Test
    void allNullFilterSelectsNothing() {
        // Given a filter column whose every row is null (so three-valued logic selects none)
        long[] values = {10, 20, 30, 40};
        Array filter = longColumn(values, new boolean[]{false, false, false, false});
        Array agg = longColumn(new long[]{1, 2, 3, 4}, null);

        // When the fused kernel runs a predicate every value would otherwise satisfy
        Number result = Compute.filteredSum(filter, new Predicate.Gt(0L), agg);

        // Then nothing is selected and the sum is the identity
        assertThat(result).isEqualTo(0L);
        assertFusedMatchesTwoPass(filter, new Predicate.Gt(0L), agg);
    }

    @Test
    void allNullAggregateContributesNothing() {
        // Given an aggregate column whose every row is null (so the reduce skips all of them)
        Array filter = longColumn(new long[]{1, 2, 3, 4}, null);
        Array agg = longColumn(new long[]{10, 20, 30, 40}, new boolean[]{false, false, false, false});

        // When the fused kernel selects rows but every aggregate value is null
        Number result = Compute.filteredSum(filter, new Predicate.Gt(0L), agg);

        // Then nothing is accumulated and the sum is the identity
        assertThat(result).isEqualTo(0L);
        assertFusedMatchesTwoPass(filter, new Predicate.Gt(0L), agg);
    }

    @Test
    void noMatchesSumsToZero() {
        // Given a predicate no filter value satisfies
        Array filter = longColumn(new long[]{1, 2, 3, 4}, null);
        Array agg = longColumn(new long[]{10, 20, 30, 40}, null);

        // When the fused kernel filters with a never-true predicate
        Number result = Compute.filteredSum(filter, new Predicate.Gt(100L), agg);

        // Then the sum is zero
        assertThat(result).isEqualTo(0L);
        assertFusedMatchesTwoPass(filter, new Predicate.Gt(100L), agg);
    }

    @Test
    void allMatchSumsEverything() {
        // Given a predicate every filter value satisfies and a fully valid aggregate
        Array filter = longColumn(new long[]{1, 2, 3, 4}, null);
        Array agg = longColumn(new long[]{10, 20, 30, 40}, null);

        // When the fused kernel filters with an always-true predicate
        Number result = Compute.filteredSum(filter, new Predicate.Gt(0L), agg);

        // Then it totals every aggregate value
        assertThat(result).isEqualTo(100L);
        assertFusedMatchesTwoPass(filter, new Predicate.Gt(0L), agg);
    }

    @Test
    void doubleFilterLongAggregateCrossesDomains() {
        // Given a double-domain filter and a long-domain aggregate (the benchmark's exact shape)
        Array filter = doubleColumn(new double[]{0.5, 1.5, 2.5, 3.5}, null);
        Array agg = longColumn(new long[]{100, 200, 300, 400}, null);

        // When the fused kernel filters price > 1.0 and sums the long measure
        Number result = Compute.filteredSum(filter, new Predicate.Gt(1.0), agg);

        // Then only the last three rows contribute, matching the two-pass path
        assertThat(result).isEqualTo(900L);
        assertFusedMatchesTwoPass(filter, new Predicate.Gt(1.0), agg);
    }

    private static void assertFusedMatchesTwoPass(Array filter, Predicate predicate, Array agg) {
        Mask mask = Compute.filter(filter, predicate, ARENA);
        Number twoPass = Compute.sum(agg, mask);
        Number fused = Compute.filteredSum(filter, predicate, agg);
        assertThat(fused)
                .as("fused %s on filter %s, agg %s", predicate, filter.dtype(), agg.dtype())
                .isEqualTo(twoPass);
    }

    private static Predicate[] everyComparison(Random random, Array filter) {
        boolean floating = filter.dtype() instanceof DType.Primitive prim && prim.ptype().isFloating();
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

    private static Array randomColumn(Random random, boolean nullable) {
        return randomColumn(random, nullable, random.nextInt(40));
    }

    private static Array randomColumn(Random random, boolean nullable, int n) {
        boolean[] valid = nullable ? randomValidity(random, n) : null;
        return switch (random.nextInt(3)) {
            case 0 -> longColumn(randomLongs(random, n), valid);
            case 1 -> intColumn(randomLongs(random, n), valid);
            default -> doubleColumn(randomDoubles(random, n), valid);
        };
    }

    private static long[] randomLongs(Random random, int n) {
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            values[i] = random.nextInt(101) - 50;
        }
        return values;
    }

    private static double[] randomDoubles(Random random, int n) {
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = random.nextInt(101) - 50;
        }
        return values;
    }

    private static boolean[] randomValidity(Random random, int n) {
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            valid[i] = random.nextInt(4) != 0;
        }
        return valid;
    }

    private static Array longColumn(long[] values, boolean[] valid) {
        MemorySegment seg = ARENA.allocate(Math.max(8L, values.length * 8L), 8);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(PTypeIO.LE_LONG, i, values[i]);
        }
        return wrap(new MaterializedLongArray(DType.I64, values.length, seg), valid);
    }

    private static Array intColumn(long[] values, boolean[] valid) {
        MemorySegment seg = ARENA.allocate(Math.max(4L, values.length * 4L), 4);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(PTypeIO.LE_INT, i, (int) values[i]);
        }
        return wrap(new MaterializedIntArray(DType.I32, values.length, seg), valid);
    }

    private static Array doubleColumn(double[] values, boolean[] valid) {
        MemorySegment seg = ARENA.allocate(Math.max(8L, values.length * 8L), 8);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(PTypeIO.LE_DOUBLE, i, values[i]);
        }
        return wrap(new MaterializedDoubleArray(DType.F64, values.length, seg), valid);
    }

    private static Array wrap(Array child, boolean[] valid) {
        if (valid == null) {
            return child;
        }
        return new MaskedArray(child, ComputeArrays.boolArray(ARENA, valid));
    }
}
