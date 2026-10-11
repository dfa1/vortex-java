package io.github.dfa1.vortex.reader.compute;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Covers [PrimitiveFilter#matchDouble(double, PrimitiveFilter.DoubleOp, double, double)]'s float
/// ordering (#406 gap 3, #518): Rust's total order, the same as `Double.compare`, where `-0.0 < 0.0`
/// and `NaN` equals itself and is the maximum.
class PrimitiveFilterTest {

    @Nested
    class Nan {

        @Test
        void sortsAboveEveryOrdinaryValue() {
            // Given a NaN value under test against an ordinary comparison value; Rust orders floats
            // totally, so NaN is the maximum (#518)
            // When / Then
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.EQ, 5.0, 0.0)).isFalse();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.NEQ, 5.0, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.LT, 5.0, 0.0)).isFalse();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.LTE, 5.0, 0.0)).isFalse();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.GT, 5.0, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.GTE, 5.0, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.BETWEEN, 0.0, 10.0)).isFalse();
        }

        @Test
        void equalsItself() {
            // Given NaN as both the value under test and the comparison value
            // When / Then total order makes NaN equal to NaN
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.EQ, Double.NaN, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.NEQ, Double.NaN, 0.0))
                    .isFalse();
        }

        @Test
        void isTheBoundAboveEveryOrdinaryValue() {
            // Given an ordinary value under test, but a NaN used as the comparison bound
            // When / Then the value is below it
            assertThat(PrimitiveFilter.matchDouble(5.0, PrimitiveFilter.DoubleOp.GT, Double.NaN, 0.0)).isFalse();
            assertThat(PrimitiveFilter.matchDouble(5.0, PrimitiveFilter.DoubleOp.LT, Double.NaN, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(5.0, PrimitiveFilter.DoubleOp.EQ, Double.NaN, 0.0)).isFalse();
        }
    }

    @Nested
    class Ordinary {

        @Test
        void everyOperatorMatchesItsTextbookCase() {
            // Given / When / Then an ordinary value satisfies each operator exactly as expected
            assertThat(PrimitiveFilter.matchDouble(5.0, PrimitiveFilter.DoubleOp.EQ, 5.0, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(5.0, PrimitiveFilter.DoubleOp.NEQ, 6.0, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(5.0, PrimitiveFilter.DoubleOp.LT, 6.0, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(5.0, PrimitiveFilter.DoubleOp.LTE, 5.0, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(5.0, PrimitiveFilter.DoubleOp.GT, 4.0, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(5.0, PrimitiveFilter.DoubleOp.GTE, 5.0, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(5.0, PrimitiveFilter.DoubleOp.BETWEEN, 0.0, 10.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(15.0, PrimitiveFilter.DoubleOp.BETWEEN, 0.0, 10.0)).isFalse();
        }

        @Test
        void negativeZeroSortsBelowPositiveZero() {
            // Given Rust's total order (-0.0 < 0.0), the same as Double.compare, not IEEE's -0.0 == 0.0
            // (#518)
            // When / Then
            assertThat(PrimitiveFilter.matchDouble(-0.0, PrimitiveFilter.DoubleOp.EQ, 0.0, 0.0)).isFalse();
            assertThat(PrimitiveFilter.matchDouble(-0.0, PrimitiveFilter.DoubleOp.NEQ, 0.0, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(-0.0, PrimitiveFilter.DoubleOp.LT, 0.0, 0.0)).isTrue();
        }
    }

    @Nested
    class LowerDouble {

        @Test
        void lowersEachComparisonKindToItsMatchingOperator() {
            // Given each comparison-leaf predicate
            // When lowered
            // Then the operator and bound(s) match the predicate's own semantics
            assertThat(PrimitiveFilter.lowerDouble(new Predicate.Eq(5.0)))
                    .isEqualTo(new PrimitiveFilter.DoubleBound(PrimitiveFilter.DoubleOp.EQ, 5.0, 0.0));
            assertThat(PrimitiveFilter.lowerDouble(new Predicate.Neq(5.0)))
                    .isEqualTo(new PrimitiveFilter.DoubleBound(PrimitiveFilter.DoubleOp.NEQ, 5.0, 0.0));
            assertThat(PrimitiveFilter.lowerDouble(new Predicate.Lt(5.0)))
                    .isEqualTo(new PrimitiveFilter.DoubleBound(PrimitiveFilter.DoubleOp.LT, 5.0, 0.0));
            assertThat(PrimitiveFilter.lowerDouble(new Predicate.Lte(5.0)))
                    .isEqualTo(new PrimitiveFilter.DoubleBound(PrimitiveFilter.DoubleOp.LTE, 5.0, 0.0));
            assertThat(PrimitiveFilter.lowerDouble(new Predicate.Gt(5.0)))
                    .isEqualTo(new PrimitiveFilter.DoubleBound(PrimitiveFilter.DoubleOp.GT, 5.0, 0.0));
            assertThat(PrimitiveFilter.lowerDouble(new Predicate.Gte(5.0)))
                    .isEqualTo(new PrimitiveFilter.DoubleBound(PrimitiveFilter.DoubleOp.GTE, 5.0, 0.0));
            assertThat(PrimitiveFilter.lowerDouble(new Predicate.Between(0.0, 10.0)))
                    .isEqualTo(new PrimitiveFilter.DoubleBound(PrimitiveFilter.DoubleOp.BETWEEN, 0.0, 10.0));
        }
    }
}
