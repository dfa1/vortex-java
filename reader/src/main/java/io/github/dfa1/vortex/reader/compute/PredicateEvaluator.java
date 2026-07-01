package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.array.Array;

/// The generic, per-element [Predicate] evaluator: it tests one [Predicate] against a single position
/// of any [Array] through the boxing value accessor, with no encoded-domain specialization.
///
/// This is the boxing fallback the fused filter-and-aggregate kernels ([FusedFilterSum] /
/// [FusedFilterAggregate]) drop to for any leaf they do not specialize on the primitive fast lane (a
/// non-primitive column, or a boolean composite). It mirrors the Rust three-valued-logic filter
/// semantics exactly: a null value makes every value predicate false, so the row is excluded; the
/// dedicated null tests read validity directly.
final class PredicateEvaluator {

    private PredicateEvaluator() {
    }

    /// Evaluates `predicate` against the single value at position `i`, recursing through the boolean
    /// composites. Value predicates short-circuit to `false` on a null position (three-valued logic);
    /// the null tests read validity directly.
    ///
    /// @param array     the array under test
    /// @param i         the zero-based position
    /// @param predicate the predicate to evaluate
    /// @return `true` if the value at `i` satisfies `predicate`
    static boolean evaluate(Array array, long i, Predicate predicate) {
        return switch (predicate) {
            case Predicate.Eq eq -> !Values.isNullAt(array, i)
                    && Compare.values(Values.valueAt(array, i), eq.value(), array.dtype()) == 0;
            case Predicate.Neq neq -> !Values.isNullAt(array, i)
                    && Compare.values(Values.valueAt(array, i), neq.value(), array.dtype()) != 0;
            case Predicate.Lt lt -> !Values.isNullAt(array, i)
                    && Compare.values(Values.valueAt(array, i), lt.value(), array.dtype()) < 0;
            case Predicate.Gt gt -> !Values.isNullAt(array, i)
                    && Compare.values(Values.valueAt(array, i), gt.value(), array.dtype()) > 0;
            case Predicate.Lte lte -> !Values.isNullAt(array, i)
                    && Compare.values(Values.valueAt(array, i), lte.value(), array.dtype()) <= 0;
            case Predicate.Gte gte -> !Values.isNullAt(array, i)
                    && Compare.values(Values.valueAt(array, i), gte.value(), array.dtype()) >= 0;
            case Predicate.Between between -> evaluateBetween(array, i, between);
            case Predicate.IsNull ignored -> Values.isNullAt(array, i);
            case Predicate.IsNotNull ignored -> !Values.isNullAt(array, i);
            case Predicate.And and -> evaluate(array, i, and.left()) && evaluate(array, i, and.right());
            case Predicate.Or or -> evaluate(array, i, or.left()) || evaluate(array, i, or.right());
        };
    }

    /// Tests the inclusive `[lo, hi]` range, reading the value once so the two bound compares share a
    /// single decode.
    ///
    /// @param array   the array under test
    /// @param i       the zero-based position
    /// @param between the range predicate
    /// @return `true` if the value at `i` lies within `[lo, hi]`
    private static boolean evaluateBetween(Array array, long i, Predicate.Between between) {
        if (Values.isNullAt(array, i)) {
            return false;
        }
        Object value = Values.valueAt(array, i);
        return Compare.values(value, between.lo(), array.dtype()) >= 0
                && Compare.values(value, between.hi(), array.dtype()) <= 0;
    }
}
