package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.compute.Utf8Order;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;

import java.math.BigDecimal;
import java.math.BigInteger;

/// Dtype-aware three-way comparison of two boxed scalar values, the ordering primitive the compute
/// kernels lower every value predicate and `MIN` / `MAX` reduction onto.
///
/// The comparison mode is keyed off the *column* dtype, never the boxed operand type: integer
/// columns compare as longs (unsigned columns via [Long#compareUnsigned(long, long)]), float columns
/// as doubles, strings in UTF-8 byte order ([Utf8Order]), decimals by value ([BigDecimal#compareTo]),
/// and everything else through the natural [Comparable] order. With no column, a [BigDecimal]
/// operand makes it a decimal compare. Keying off the column
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
    /// @param column the dtype that decides the comparison mode (unsigned, floating, decimal, or natural), or
    ///               `null` to compare width-agnostically off the operands (numbers as longs unless
    ///               either side is floating, otherwise the natural [Comparable] order)
    /// @return a negative, zero, or positive integer as `a` is less than, equal to, or greater than `b`
    /// @throws VortexException if the two values are not mutually comparable
    @SuppressWarnings("unchecked")
    public static int values(Object a, Object b, DType column) {
        // a decimal is a Number, but its order is its value, never the long it truncates to; a
        // primitive column keeps its own order, whatever the operand's type
        if ((a instanceof BigDecimal || b instanceof BigDecimal) && !(column instanceof DType.Primitive)) {
            return decimal(a).compareTo(decimal(b));
        }
        if (a instanceof Number na && b instanceof Number nb) {
            if (column instanceof DType.Primitive prim) {
                if (prim.ptype().isFloating()) {
                    return Double.compare(na.doubleValue(), nb.doubleValue());
                }
                // U64 values store the raw 64 bits, so a value >= 2^63 is a negative Long; an
                // unsigned column must compare unsigned. U8/U16/U32 are zero-extended to a positive
                // Long where signed == unsigned, so this stays correct for them too.
                long la = integer(a);
                long lb = integer(b);
                return column.isUnsigned() ? Long.compareUnsigned(la, lb) : Long.compare(la, lb);
            }
            // Column type unresolved — fall back to a width-agnostic compare keyed off the operands
            // so two valid numbers never drop into the throwing path.
            if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            return Long.compare(na.longValue(), nb.longValue());
        }
        if (a instanceof String sa && b instanceof String sb) {
            // UTF-8 byte order, the order Rust writes string stats in, not String#compareTo's UTF-16.
            return Utf8Order.compare(sa, sb);
        }
        try {
            return ((Comparable<Object>) a).compareTo(b);
        } catch (ClassCastException e) {
            throw new VortexException("compute value of type " + className(b)
                    + " is not comparable to the array value of type " + className(a), e);
        }
    }

    /// A decimal-column operand as a [BigDecimal]: a decimal itself, an integer of any width, or a
    /// finite float or double. Anything else, a NaN or an infinity among them, has no decimal value.
    private static BigDecimal decimal(Object value) {
        return switch (value) {
            case null -> throw new VortexException("compute value of type null is not comparable to a decimal");
            case BigDecimal d -> d;
            case BigInteger b -> new BigDecimal(b);
            case Byte _, Short _, Integer _, Long _ -> BigDecimal.valueOf(((Number) value).longValue());
            case Double d when Double.isFinite(d) -> BigDecimal.valueOf(d);
            case Float f when Float.isFinite(f) -> new BigDecimal(f.toString());
            default -> throw new VortexException("compute value of type " + className(value)
                    + " (" + value + ") is not comparable to a decimal");
        };
    }

    /// An integer-column operand as a long. Integers of any width are accepted, as everywhere in the
    /// compute API; a float, double or decimal is rejected, as Rust's compare requires both sides to
    /// share a dtype: truncating it would make `x >= 1.5` match `x = 1` (#701).
    ///
    /// @param value the operand
    /// @return its long value
    /// @throws VortexException if `value` is not a `Long`, `Integer`, `Short` or `Byte`
    static long integer(Object value) {
        return switch (value) {
            case Long l -> l;
            case Integer i -> i;
            case Short s -> s;
            case Byte b -> b;
            default -> throw new VortexException("an integer column compares only with an integer value, got "
                    + className(value) + " (" + value + ")");
        };
    }

    /// Returns the simple class name of `value`, or `"null"` when it is absent, for error messages.
    ///
    /// @param value the value to describe
    /// @return the simple class name, or `"null"`
    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
