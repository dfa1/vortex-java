package io.github.dfa1.vortex.reader.compute;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Covers [PrimitiveFilter#matchDouble(double, PrimitiveFilter.DoubleOp, double, double)]'s
/// `NaN` handling (issue #406 gap 3): a `NaN` value must never satisfy an ordering comparison
/// (IEEE 754), unlike [Compare#values(Object, Object, io.github.dfa1.vortex.core.model.DType)]'s
/// `Double.compare` convention, which exists for `MIN`/`MAX`/sort and treats `NaN` as the maximum.
class PrimitiveFilterTest {

    @Nested
    class Nan {

        @Test
        void neverSatisfiesAnOrderingOperator() {
            // Given a NaN value under test against an ordinary comparison value
            // When testing every ordering operator (not NEQ — see neqIsTrueForNan below)
            // Then none of them match
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.EQ, 5.0, 0.0)).isFalse();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.LT, 5.0, 0.0)).isFalse();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.LTE, 5.0, 0.0)).isFalse();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.GT, 5.0, 0.0)).isFalse();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.GTE, 5.0, 0.0)).isFalse();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.BETWEEN, 0.0, 10.0)).isFalse();
        }

        @Test
        void neqIsTrueForNan() {
            // Given a NaN value under test: NaN == x is false for every x, including NaN itself, so
            // NEQ ("not equal", the negation of EQ) is true — this is IEEE 754, not a bug.
            // When / Then
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.NEQ, 5.0, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(Double.NaN, PrimitiveFilter.DoubleOp.NEQ, Double.NaN, 0.0))
                    .isTrue();
        }

        @Test
        void neverSatisfiesAnOrderingOperatorAsTheBoundEither() {
            // Given an ordinary value under test, but a NaN used as the comparison bound
            // When / Then every ordering comparison is still false in either direction
            assertThat(PrimitiveFilter.matchDouble(5.0, PrimitiveFilter.DoubleOp.GT, Double.NaN, 0.0)).isFalse();
            assertThat(PrimitiveFilter.matchDouble(5.0, PrimitiveFilter.DoubleOp.LT, Double.NaN, 0.0)).isFalse();
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
        void negativeAndPositiveZeroCompareEqual() {
            // Given IEEE 754's -0.0 == 0.0 (unlike Double.compare, which orders them apart to give
            // sorting a total order) — predicate matching must follow the native operator's
            // definition of equality, not the sort order's.
            // When / Then
            assertThat(PrimitiveFilter.matchDouble(-0.0, PrimitiveFilter.DoubleOp.EQ, 0.0, 0.0)).isTrue();
            assertThat(PrimitiveFilter.matchDouble(-0.0, PrimitiveFilter.DoubleOp.NEQ, 0.0, 0.0)).isFalse();
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
