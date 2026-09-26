package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.model.DType;
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
            case Predicate.IsNull _ -> Values.isNullAt(array, i);
            case Predicate.IsNotNull _ -> !Values.isNullAt(array, i);
            case Predicate.And(var left, var right) -> evaluate(array, i, left) && evaluate(array, i, right);
            case Predicate.Or(var left, var right) -> evaluate(array, i, left) || evaluate(array, i, right);
            default -> !Values.isNullAt(array, i) && matchesValue(array, i, predicate);
        };
    }

    /// Tests a non-null value at position `i` against a value-comparison leaf (`Eq` / `Neq` / `Lt` /
    /// `Gt` / `Lte` / `Gte` / `Between`).
    ///
    /// A floating column dispatches through [PrimitiveFilter#lowerDouble(Predicate)] /
    /// [PrimitiveFilter#matchDouble(double, PrimitiveFilter.DoubleOp, double, double)] — native
    /// `<`/`>`/`==`/… double comparisons, IEEE-correct: a `NaN` value never satisfies an ordering
    /// test. This is deliberately *not* routed through [Compare#values(Object, Object, DType)]: that
    /// method reports a single three-way result, and no such result can make every derived
    /// `<,>,<=,>=,==` comparison come out false at once the way IEEE's unordered `NaN` comparisons
    /// require — [Compare#values(Object, Object, DType)]'s `Double.compare` ordering is correct for
    /// `MIN`/`MAX`/sort (where a total order is required) but not for testing whether a value
    /// satisfies a predicate. Every other column type keeps the existing width-agnostic
    /// [Compare#values(Object, Object, DType)] dispatch.
    ///
    /// @param array     the array under test
    /// @param i         the zero-based, non-null position
    /// @param predicate the value-comparison leaf to test
    /// @return `true` if the value at `i` satisfies `predicate`
    private static boolean matchesValue(Array array, long i, Predicate predicate) {
        if (array.dtype() instanceof DType.Primitive p && p.ptype().isFloating()) {
            double v = ((Number) Values.valueAt(array, i)).doubleValue();
            PrimitiveFilter.DoubleBound bound = PrimitiveFilter.lowerDouble(predicate);
            return PrimitiveFilter.matchDouble(v, bound.op(), bound.lo(), bound.hi());
        }
        Object at = Values.valueAt(array, i);
        return switch (predicate) {
            case Predicate.Eq(var value) -> Compare.values(at, value, array.dtype()) == 0;
            case Predicate.Neq(var value) -> Compare.values(at, value, array.dtype()) != 0;
            case Predicate.Lt(var value) -> Compare.values(at, value, array.dtype()) < 0;
            case Predicate.Gt(var value) -> Compare.values(at, value, array.dtype()) > 0;
            case Predicate.Lte(var value) -> Compare.values(at, value, array.dtype()) <= 0;
            case Predicate.Gte(var value) -> Compare.values(at, value, array.dtype()) >= 0;
            case Predicate.Between(var lo, var hi) ->
                    Compare.values(at, lo, array.dtype()) >= 0 && Compare.values(at, hi, array.dtype()) <= 0;
            default -> throw new IllegalStateException("unreachable non-value predicate: " + predicate);
        };
    }
}
