package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;

/// Dtype-aware three-way comparison of two boxed scalar values, the ordering primitive the compute
/// kernels lower every value predicate and `MIN` / `MAX` reduction onto.
///
/// The comparison mode is keyed off the *column* dtype, never the boxed operand type: integer
/// columns compare as longs (unsigned columns via [Long#compareUnsigned(long, long)]), float columns
/// as doubles, and everything else through the natural [Comparable] order. Keying off the column
/// keeps the compare width-agnostic — a caller may box a filter value at any integer width — and
/// never routes an integer column through double-compare, which would lose precision past 2^53.
///
/// This is the single source of truth for scalar ordering across the reader module: zone-map chunk
/// pruning, the compute filter / `MIN` / `MAX` kernels, and the Calcite aggregate push-down all route
/// through it, so the same operand order can never drift between pruning and the result it gates.
public final class Compare {

    private Compare() {
    }

    /// Compares `a` against `b` under the ordering implied by `column`.
    ///
    /// @param a      the left value, typically the array element under test
    /// @param b      the right value, typically the predicate operand
    /// @param column the dtype that decides the comparison mode (unsigned, floating, or natural), or
    ///               `null` to compare width-agnostically off the operands (numbers as longs unless
    ///               either side is floating, otherwise the natural [Comparable] order)
    /// @return a negative, zero, or positive integer as `a` is less than, equal to, or greater than `b`
    /// @throws VortexException if the two values are not mutually comparable
    @SuppressWarnings("unchecked")
    public static int values(Object a, Object b, DType column) {
        if (a instanceof Number na && b instanceof Number nb) {
            if (column instanceof DType.Primitive prim) {
                if (prim.ptype().isFloating()) {
                    return Double.compare(na.doubleValue(), nb.doubleValue());
                }
                // U64 values store the raw 64 bits, so a value >= 2^63 is a negative Long; an
                // unsigned column must compare unsigned. U8/U16/U32 are zero-extended to a positive
                // Long where signed == unsigned, so this stays correct for them too.
                return column.isUnsigned()
                        ? Long.compareUnsigned(na.longValue(), nb.longValue())
                        : Long.compare(na.longValue(), nb.longValue());
            }
            // Column type unresolved — fall back to a width-agnostic compare keyed off the operands
            // so two valid numbers never drop into the throwing path.
            if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            return Long.compare(na.longValue(), nb.longValue());
        }
        try {
            return ((Comparable<Object>) a).compareTo(b);
        } catch (ClassCastException e) {
            throw new VortexException("compute value of type " + className(b)
                    + " is not comparable to the array value of type " + className(a), e);
        }
    }

    /// Returns the simple class name of `value`, or `"null"` when it is absent, for error messages.
    ///
    /// @param value the value to describe
    /// @return the simple class name, or `"null"`
    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
