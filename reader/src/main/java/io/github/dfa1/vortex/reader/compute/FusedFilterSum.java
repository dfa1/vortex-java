package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.ShortArray;

/// The fused, one-pass filter-and-sum kernel: it filters one column by a [Predicate] and totals a
/// second column over the selected rows in a single scan, with no intermediate selection bitmap and
/// no second pass.
///
/// For each row it evaluates the predicate on the filter value and, when the row is selected and the
/// aggregate value is non-null, folds the aggregate value straight into the running total — nothing is
/// materialized between the filter and the reduce. The semantics are:
/// - the filter uses three-valued logic — a null filter row is never selected;
/// - the aggregate skips its own nulls;
/// - the aggregate must be a numeric primitive (guarded by [NumericColumns#requireNumeric(DType)]),
///   and the result is a [Long] for an integer aggregate or a [Double] for a floating one — the same
///   return-type rule as `SUM`.
///
/// The hot case is type-specialized into boxing-free loops: a primitive long- or double-domain filter
/// column against a [LongArray] / [DoubleArray] / [FloatArray] aggregate column. The predicate is
/// lowered once (reusing [PrimitiveFilter#lowerLong(Predicate, long)] /
/// [PrimitiveFilter#lowerDouble(Predicate)]), the signedness flip and the aggregate accessor are
/// hoisted out of the loop (the hot-loop rule's branch-split idiom), and the inner body is a bare
/// `if (test) acc += agg.getLong(i)` / `agg.getDouble(i)`. Any other shape (non-primitive filter,
/// narrow-int or `f16` aggregate, a composite or null-test predicate) falls back to the generic
/// boxing path, which reuses [PredicateEvaluator#evaluate(Array, long, Predicate)] and [Values].
final class FusedFilterSum {

    private FusedFilterSum() {
    }

    /// Filters `filterColumn` by `predicate` and sums `aggColumn` over the selected non-null rows in a
    /// single pass.
    ///
    /// @param filterColumn the column the predicate tests
    /// @param predicate    the predicate that selects rows
    /// @param aggColumn    the numeric column to total over the selected rows, of the same length as
    ///                     `filterColumn`
    /// @return the sum as a [Long] for an integer aggregate or a [Double] for a floating one; the
    ///         additive identity (`0`) when no row is both selected and non-null
    /// @throws VortexException if the two columns differ in length or `aggColumn` is not numeric
    static Number filteredSum(Array filterColumn, Predicate predicate, Array aggColumn) {
        long n = filterColumn.length();
        if (aggColumn.length() != n) {
            throw new VortexException("filteredSum: filter length " + n
                    + " != aggregate length " + aggColumn.length());
        }
        NumericColumns.requireNumeric(aggColumn.dtype());

        Array aggData = NumericColumns.unwrap(aggColumn);
        BoolArray aggValidity = NumericColumns.validityOf(aggColumn);
        boolean aggDouble = aggData instanceof DoubleArray || aggData instanceof FloatArray;

        Array filterData = NumericColumns.unwrap(filterColumn);
        BoolArray filterValidity = NumericColumns.validityOf(filterColumn);

        // Hot lane: a primitive filter column, a comparison-leaf predicate over numeric constants, and
        // an aggregate read by a single typed accessor (i64/u64 long, f64 double, or f32 float). Any
        // other shape drops to the generic boxing path with no behavioral drift.
        if (isComparisonLeaf(predicate) && PrimitiveFilter.predicateOk(predicate)
                && isPrimitiveFilter(filterData)
                && (aggData instanceof LongArray || aggDouble)) {
            return fused(filterData, filterValidity, predicate, aggData, aggValidity, aggDouble, n);
        }
        return generic(filterColumn, predicate, aggColumn, n);
    }

    /// Dispatches the specialized fused loop on the filter and aggregate value domains, lowering the
    /// predicate once outside every loop.
    ///
    /// @param fData     the unwrapped primitive filter array
    /// @param fVal      the filter validity bitmap, or `null` when every filter row is valid
    /// @param predicate the comparison-leaf predicate
    /// @param aData     the unwrapped numeric aggregate array
    /// @param aVal      the aggregate validity bitmap, or `null` when every aggregate row is valid
    /// @param aggDouble whether the aggregate folds into a double (a [DoubleArray] / [FloatArray])
    /// @param n         the row count
    /// @return the fused sum boxed as a [Long] (integer aggregate) or [Double] (floating aggregate)
    private static Number fused(Array fData, BoolArray fVal, Predicate predicate,
                                Array aData, BoolArray aVal, boolean aggDouble, long n) {
        // Explicit branches, not a ternary: a `Double ? : Long` conditional would binary-numeric
        // promote both arms to double and re-box to Double, losing the long aggregate's result type.
        if (fData instanceof DoubleArray || fData instanceof FloatArray) {
            PrimitiveFilter.DoubleBound bound = PrimitiveFilter.lowerDouble(predicate);
            if (aggDouble) {
                return Double.valueOf(doubleFilterDoubleAgg(fData, fVal, bound, aData, aVal, n));
            }
            return Long.valueOf(doubleFilterLongAgg(fData, fVal, bound, (LongArray) aData, aVal, n));
        }
        boolean unsigned = fData.dtype().isUnsigned();
        long flip = unsigned ? Long.MIN_VALUE : 0L;
        PrimitiveFilter.LongRange range = PrimitiveFilter.lowerLong(predicate, flip);
        if (aggDouble) {
            return Double.valueOf(longFilterDoubleAgg(fData, fVal, range, flip, unsigned, aData, aVal, n));
        }
        return Long.valueOf(longFilterLongAgg(fData, fVal, range, flip, unsigned, (LongArray) aData, aVal, n));
    }

    // -------------------------------------------------------------------------------------------------
    // Specialized fused loops. Each dispatches the concrete filter array once (the 100%-of-rows read),
    // then runs a bare loop whose only per-row work is the hoisted range/op test and, behind it, the
    // single typed aggregate add.
    // -------------------------------------------------------------------------------------------------

    /// Long-domain filter, long-domain aggregate: the predicate lowers to an inclusive flipped-long
    /// range, the aggregate folds into a wrapping [Long] sum.
    ///
    /// @param fData    the unwrapped long-domain filter array
    /// @param fVal     the filter validity bitmap, or `null`
    /// @param range    the lowered inclusive range on the flipped longs
    /// @param flip     the order-preserving XOR flip (`Long.MIN_VALUE` unsigned, `0` signed)
    /// @param unsigned whether the filter column is unsigned (narrow types zero-extend)
    /// @param agg      the aggregate array
    /// @param aVal     the aggregate validity bitmap, or `null`
    /// @param n        the row count
    /// @return the wrapping integer sum over the selected non-null rows
    private static long longFilterLongAgg(Array fData, BoolArray fVal, PrimitiveFilter.LongRange range,
                                          long flip, boolean unsigned, LongArray agg, BoolArray aVal, long n) {
        long lo = range.lo();
        long hi = range.hi();
        boolean negate = range.negate();
        boolean noFilterNull = fVal == null;
        boolean noAggNull = aVal == null;
        long acc = 0L;
        if (fData instanceof LongArray la) {
            for (long i = 0; i < n; i++) {
                long v = la.getLong(i) ^ flip;
                if (selectedLong(lo, hi, v, negate, noFilterNull, fVal, i) && (noAggNull || aVal.getBoolean(i))) {
                    acc += agg.getLong(i);
                }
            }
        } else if (fData instanceof IntArray ia) {
            for (long i = 0; i < n; i++) {
                long v = (unsigned ? (ia.getInt(i) & 0xFFFFFFFFL) : ia.getInt(i)) ^ flip;
                if (selectedLong(lo, hi, v, negate, noFilterNull, fVal, i) && (noAggNull || aVal.getBoolean(i))) {
                    acc += agg.getLong(i);
                }
            }
        } else if (fData instanceof ShortArray sa) {
            for (long i = 0; i < n; i++) {
                long v = (unsigned ? (sa.getShort(i) & 0xFFFFL) : sa.getShort(i)) ^ flip;
                if (selectedLong(lo, hi, v, negate, noFilterNull, fVal, i) && (noAggNull || aVal.getBoolean(i))) {
                    acc += agg.getLong(i);
                }
            }
        } else {
            ByteArray ba = (ByteArray) fData;
            for (long i = 0; i < n; i++) {
                long v = (unsigned ? (ba.getByte(i) & 0xFFL) : ba.getByte(i)) ^ flip;
                if (selectedLong(lo, hi, v, negate, noFilterNull, fVal, i) && (noAggNull || aVal.getBoolean(i))) {
                    acc += agg.getLong(i);
                }
            }
        }
        return acc;
    }

    /// Long-domain filter, double-domain aggregate: the aggregate folds into a [Double] sum.
    ///
    /// @param fData    the unwrapped long-domain filter array
    /// @param fVal     the filter validity bitmap, or `null`
    /// @param range    the lowered inclusive range on the flipped longs
    /// @param flip     the order-preserving XOR flip
    /// @param unsigned whether the filter column is unsigned
    /// @param aData    the unwrapped double-domain aggregate array
    /// @param aVal     the aggregate validity bitmap, or `null`
    /// @param n        the row count
    /// @return the floating sum over the selected non-null rows
    private static double longFilterDoubleAgg(Array fData, BoolArray fVal, PrimitiveFilter.LongRange range,
                                              long flip, boolean unsigned, Array aData, BoolArray aVal, long n) {
        long lo = range.lo();
        long hi = range.hi();
        boolean negate = range.negate();
        boolean noFilterNull = fVal == null;
        boolean noAggNull = aVal == null;
        boolean aggIsFloat = aData instanceof FloatArray;
        double acc = 0.0;
        if (fData instanceof LongArray la) {
            for (long i = 0; i < n; i++) {
                long v = la.getLong(i) ^ flip;
                if (selectedLong(lo, hi, v, negate, noFilterNull, fVal, i) && (noAggNull || aVal.getBoolean(i))) {
                    acc += aggDoubleValue(aData, aggIsFloat, i);
                }
            }
        } else if (fData instanceof IntArray ia) {
            for (long i = 0; i < n; i++) {
                long v = (unsigned ? (ia.getInt(i) & 0xFFFFFFFFL) : ia.getInt(i)) ^ flip;
                if (selectedLong(lo, hi, v, negate, noFilterNull, fVal, i) && (noAggNull || aVal.getBoolean(i))) {
                    acc += aggDoubleValue(aData, aggIsFloat, i);
                }
            }
        } else if (fData instanceof ShortArray sa) {
            for (long i = 0; i < n; i++) {
                long v = (unsigned ? (sa.getShort(i) & 0xFFFFL) : sa.getShort(i)) ^ flip;
                if (selectedLong(lo, hi, v, negate, noFilterNull, fVal, i) && (noAggNull || aVal.getBoolean(i))) {
                    acc += aggDoubleValue(aData, aggIsFloat, i);
                }
            }
        } else {
            ByteArray ba = (ByteArray) fData;
            for (long i = 0; i < n; i++) {
                long v = (unsigned ? (ba.getByte(i) & 0xFFL) : ba.getByte(i)) ^ flip;
                if (selectedLong(lo, hi, v, negate, noFilterNull, fVal, i) && (noAggNull || aVal.getBoolean(i))) {
                    acc += aggDoubleValue(aData, aggIsFloat, i);
                }
            }
        }
        return acc;
    }

    /// Double-domain filter, long-domain aggregate: the predicate lowers to a hoisted double operator,
    /// the aggregate folds into a wrapping [Long] sum.
    ///
    /// @param fData the unwrapped double-domain filter array
    /// @param fVal  the filter validity bitmap, or `null`
    /// @param bound the lowered double operator and bounds
    /// @param agg   the aggregate array
    /// @param aVal  the aggregate validity bitmap, or `null`
    /// @param n     the row count
    /// @return the wrapping integer sum over the selected non-null rows
    private static long doubleFilterLongAgg(Array fData, BoolArray fVal, PrimitiveFilter.DoubleBound bound,
                                            LongArray agg, BoolArray aVal, long n) {
        PrimitiveFilter.DoubleOp op = bound.op();
        double lo = bound.lo();
        double hi = bound.hi();
        boolean noFilterNull = fVal == null;
        boolean noAggNull = aVal == null;
        long acc = 0L;
        if (fData instanceof DoubleArray da) {
            for (long i = 0; i < n; i++) {
                if (selectedDouble(da.getDouble(i), op, lo, hi, noFilterNull, fVal, i)
                        && (noAggNull || aVal.getBoolean(i))) {
                    acc += agg.getLong(i);
                }
            }
        } else {
            FloatArray fa = (FloatArray) fData;
            for (long i = 0; i < n; i++) {
                if (selectedDouble(fa.getFloat(i), op, lo, hi, noFilterNull, fVal, i)
                        && (noAggNull || aVal.getBoolean(i))) {
                    acc += agg.getLong(i);
                }
            }
        }
        return acc;
    }

    /// Double-domain filter, double-domain aggregate: both sides stay in the double domain.
    ///
    /// @param fData the unwrapped double-domain filter array
    /// @param fVal  the filter validity bitmap, or `null`
    /// @param bound the lowered double operator and bounds
    /// @param aData the unwrapped double-domain aggregate array
    /// @param aVal  the aggregate validity bitmap, or `null`
    /// @param n     the row count
    /// @return the floating sum over the selected non-null rows
    private static double doubleFilterDoubleAgg(Array fData, BoolArray fVal, PrimitiveFilter.DoubleBound bound,
                                                Array aData, BoolArray aVal, long n) {
        PrimitiveFilter.DoubleOp op = bound.op();
        double lo = bound.lo();
        double hi = bound.hi();
        boolean noFilterNull = fVal == null;
        boolean noAggNull = aVal == null;
        boolean aggIsFloat = aData instanceof FloatArray;
        double acc = 0.0;
        if (fData instanceof DoubleArray da) {
            for (long i = 0; i < n; i++) {
                if (selectedDouble(da.getDouble(i), op, lo, hi, noFilterNull, fVal, i)
                        && (noAggNull || aVal.getBoolean(i))) {
                    acc += aggDoubleValue(aData, aggIsFloat, i);
                }
            }
        } else {
            FloatArray fa = (FloatArray) fData;
            for (long i = 0; i < n; i++) {
                if (selectedDouble(fa.getFloat(i), op, lo, hi, noFilterNull, fVal, i)
                        && (noAggNull || aVal.getBoolean(i))) {
                    acc += aggDoubleValue(aData, aggIsFloat, i);
                }
            }
        }
        return acc;
    }

    /// Reports whether a long-domain filter position is selected: in range (allowing for Neq's
    /// complement) and not a null filter row.
    ///
    /// @param lo           the inclusive lower bound in the flipped domain
    /// @param hi           the inclusive upper bound in the flipped domain
    /// @param v            the flipped filter value
    /// @param negate       `true` to select the complement of the range (Neq)
    /// @param noFilterNull `true` when the filter column has no validity bitmap
    /// @param fVal         the filter validity bitmap, read only when `noFilterNull` is `false`
    /// @param i            the zero-based position
    /// @return `true` if position `i` passes the filter
    private static boolean selectedLong(long lo, long hi, long v, boolean negate,
                                        boolean noFilterNull, BoolArray fVal, long i) {
        return ((lo <= v && v <= hi) != negate) && (noFilterNull || fVal.getBoolean(i));
    }

    /// Reports whether a double-domain filter position is selected: it matches the lowered operator
    /// (under [Double#compare(double, double)] order) and is not a null filter row.
    ///
    /// @param v            the filter value
    /// @param op           the lowered operator
    /// @param lo           the comparison value (and lower bound for a between)
    /// @param hi           the upper bound, used only for a between
    /// @param noFilterNull `true` when the filter column has no validity bitmap
    /// @param fVal         the filter validity bitmap, read only when `noFilterNull` is `false`
    /// @param i            the zero-based position
    /// @return `true` if position `i` passes the filter
    private static boolean selectedDouble(double v, PrimitiveFilter.DoubleOp op, double lo, double hi,
                                          boolean noFilterNull, BoolArray fVal, long i) {
        return PrimitiveFilter.matchDouble(v, op, lo, hi) && (noFilterNull || fVal.getBoolean(i));
    }

    /// Reads aggregate position `i` as a double through the hoisted typed accessor.
    ///
    /// @param aData      the unwrapped double-domain aggregate array
    /// @param aggIsFloat whether the aggregate is an `f32` ([FloatArray]) rather than `f64`
    /// @param i          the zero-based position
    /// @return the aggregate value widened to a double
    private static double aggDoubleValue(Array aData, boolean aggIsFloat, long i) {
        return aggIsFloat ? ((FloatArray) aData).getFloat(i) : ((DoubleArray) aData).getDouble(i);
    }

    /// The generic boxing fallback for inputs the fast lane does not specialize, reusing the
    /// per-element predicate evaluator and the boxing value accessor so it stays bit-identical to the
    /// fast lane.
    ///
    /// @param filterColumn the column the predicate tests (possibly masked or non-primitive)
    /// @param predicate    the predicate that selects rows
    /// @param aggColumn    the numeric aggregate column
    /// @param n            the row count
    /// @return the sum as a [Long] (integer aggregate) or [Double] (floating aggregate)
    private static Number generic(Array filterColumn, Predicate predicate, Array aggColumn, long n) {
        if (NumericColumns.isFloating(aggColumn)) {
            double acc = 0.0;
            for (long i = 0; i < n; i++) {
                if (PredicateEvaluator.evaluate(filterColumn, i, predicate) && !Values.isNullAt(aggColumn, i)) {
                    acc += ((Number) Values.valueAt(aggColumn, i)).doubleValue();
                }
            }
            return acc;
        }
        long acc = 0L;
        for (long i = 0; i < n; i++) {
            if (PredicateEvaluator.evaluate(filterColumn, i, predicate) && !Values.isNullAt(aggColumn, i)) {
                acc += ((Number) Values.valueAt(aggColumn, i)).longValue();
            }
        }
        return acc;
    }

    /// Reports whether the predicate is a single value-comparison leaf the fast lane lowers — not a
    /// null test or a boolean composite, which the generic path handles.
    ///
    /// @param predicate the predicate to classify
    /// @return `true` for Eq / Neq / Lt / Gt / Lte / Gte / Between
    private static boolean isComparisonLeaf(Predicate predicate) {
        return predicate instanceof Predicate.Eq || predicate instanceof Predicate.Neq
                || predicate instanceof Predicate.Lt || predicate instanceof Predicate.Gt
                || predicate instanceof Predicate.Lte || predicate instanceof Predicate.Gte
                || predicate instanceof Predicate.Between;
    }

    /// Reports whether the unwrapped filter array is a primitive long- or double-domain column the
    /// fast lane reads through a typed accessor.
    ///
    /// @param data the unwrapped filter array
    /// @return `true` for a primitive long- or double-domain array
    private static boolean isPrimitiveFilter(Array data) {
        return data.dtype() instanceof DType.Primitive
                && (NumericColumns.isLongDomain(data) || data instanceof DoubleArray || data instanceof FloatArray);
    }
}
