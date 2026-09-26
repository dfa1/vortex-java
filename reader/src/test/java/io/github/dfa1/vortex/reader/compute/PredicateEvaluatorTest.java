package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.array.Array;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static org.assertj.core.api.Assertions.assertThat;

/// Covers [PredicateEvaluator]'s per-row dispatch for floating columns (issue #406 gap 3): a `NaN`
/// value must never satisfy an ordering predicate, and the null check still takes precedence over
/// it for a nullable column. Non-floating columns keep routing through
/// [Compare#values(Object, Object, io.github.dfa1.vortex.core.model.DType)] unchanged.
class PredicateEvaluatorTest {

    private static final Arena ARENA = Arena.ofAuto();

    @Nested
    class FloatingNan {

        @Test
        void nanRowNeverMatchesAnOrderingPredicate() {
            // Given an f64 column with one NaN row among ordinary values
            Array column = ComputeArrays.doubleArray(ARENA, 1.0, Double.NaN, 3.0);

            // When testing the NaN row (index 1) against every ordering predicate
            // Then none match
            assertThat(PredicateEvaluator.evaluate(column, 1, new Predicate.Eq(2.0))).isFalse();
            assertThat(PredicateEvaluator.evaluate(column, 1, new Predicate.Lt(2.0))).isFalse();
            assertThat(PredicateEvaluator.evaluate(column, 1, new Predicate.Lte(2.0))).isFalse();
            assertThat(PredicateEvaluator.evaluate(column, 1, new Predicate.Gt(2.0))).isFalse();
            assertThat(PredicateEvaluator.evaluate(column, 1, new Predicate.Gte(2.0))).isFalse();
            assertThat(PredicateEvaluator.evaluate(column, 1, new Predicate.Between(0.0, 10.0))).isFalse();
        }

        @Test
        void nanRowMatchesNeqAgainstAnyValueIncludingItself() {
            // Given an f64 column whose only row is NaN
            Array column = ComputeArrays.doubleArray(ARENA, Double.NaN);

            // When testing Neq against an ordinary value and against NaN itself
            // Then both match: NaN == x is false for every x, so "not equal" is true (IEEE, not a bug)
            assertThat(PredicateEvaluator.evaluate(column, 0, new Predicate.Neq(2.0))).isTrue();
            assertThat(PredicateEvaluator.evaluate(column, 0, new Predicate.Neq(Double.NaN))).isTrue();
        }

        @Test
        void ordinaryNeighborRowsMatchNormally() {
            // Given an f64 column with a NaN row sitting between two ordinary rows
            Array column = ComputeArrays.doubleArray(ARENA, 1.0, Double.NaN, 3.0);

            // When testing the ordinary rows
            // Then the NaN neighbor doesn't interfere with their own comparisons
            assertThat(PredicateEvaluator.evaluate(column, 0, new Predicate.Lt(2.0))).isTrue();
            assertThat(PredicateEvaluator.evaluate(column, 2, new Predicate.Gt(2.0))).isTrue();
        }

        @Test
        void nullTakesPrecedenceOverNanOnAMaskedColumn() {
            // Given a nullable f64 column where the NaN-holding row is also marked invalid
            Array column = ComputeArrays.maskedDoubleArray(ARENA,
                    new double[]{1.0, Double.NaN}, new boolean[]{true, false});

            // When testing the null row against any value predicate
            // Then it is excluded by the null check before the floating branch ever runs — same
            // result (false) either way here, but for the right reason (three-valued logic, not the
            // NaN handling)
            assertThat(PredicateEvaluator.evaluate(column, 1, new Predicate.Gt(0.0))).isFalse();
            assertThat(PredicateEvaluator.evaluate(column, 1, new Predicate.Neq(0.0))).isFalse();
        }
    }

    @Nested
    class NonFloatingRegression {

        @Test
        void integerColumnStillRoutesThroughCompareValues() {
            // Given a plain i64 column
            Array column = ComputeArrays.longArray(ARENA, 10, 20, 30);

            // When / Then ordinary comparisons work exactly as before the floating-column dispatch
            // was added
            assertThat(PredicateEvaluator.evaluate(column, 1, new Predicate.Eq(20L))).isTrue();
            assertThat(PredicateEvaluator.evaluate(column, 0, new Predicate.Lt(20L))).isTrue();
            assertThat(PredicateEvaluator.evaluate(column, 2, new Predicate.Gte(30L))).isTrue();
            assertThat(PredicateEvaluator.evaluate(column, 0, new Predicate.Neq(20L))).isTrue();
        }

        @Test
        void nullIntegerRowNeverMatchesAValuePredicate() {
            // Given a nullable i64 column with one null row
            Array column = ComputeArrays.maskedLongArray(ARENA, new long[]{10, 20}, new boolean[]{true, false});

            // When / Then the null row is excluded regardless of the predicate kind
            assertThat(PredicateEvaluator.evaluate(column, 1, new Predicate.Gt(0L))).isFalse();
            assertThat(PredicateEvaluator.evaluate(column, 1, new Predicate.IsNull())).isTrue();
        }
    }
}
