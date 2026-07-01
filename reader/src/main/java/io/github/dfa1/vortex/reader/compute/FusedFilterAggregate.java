package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;

import java.util.ArrayList;
import java.util.List;

/// The fused, one-pass multi-column filter-and-aggregate kernel: it evaluates a whole [RowFilter]
/// (an n-ary `AND` of column-bound [Predicate] leaves, or a single leaf) and, over the rows it
/// selects, folds an optional aggregate column's `SUM` / `MIN` / `MAX` / non-null count — all in a
/// single scan of the chunk's rows, with no intermediate [Mask] and no second pass.
///
/// This generalizes [FusedFilterSum] from one filter + one aggregate to many filters + one
/// aggregate, and adds the `MIN` / `MAX` / count folds the Calcite boundary aggregate push-down
/// (ADR 0013 §6 tier 2) needs. Where the deprecated two-pass path built one [Mask] per filter leaf,
/// intersected them, and then re-scanned the aggregate column under the combined mask once per
/// reduction, this kernel resolves every leaf's column and lowers every leaf's [Predicate] once,
/// then runs a single row loop: for each row it evaluates the leaves (short-circuiting on the first
/// that rejects the row) and, when the whole filter accepts the row and the aggregate value is
/// non-null, folds that value straight into the running totals. Nothing is materialized between the
/// filter and the reduce.
///
/// Semantics are identical to the two-pass path, by construction:
/// - the filter is an n-ary `AND` under three-valued logic — a row is selected only when every leaf
///   accepts it, and a null in any filter column rejects that leaf (exactly as
///   [Compute#filter(Array, Predicate, java.lang.foreign.Arena)] excludes null positions);
/// - the aggregate skips its own nulls (exactly as [Compute#sum(Array, Mask)] /
///   [Compute#count(Array, Mask)] do);
/// - `SUM` covers numeric primitives only — a [Long] for an integer aggregate, a [Double] for a
///   floating one (an `f16` column without a specialized lane folds a boxing `doubleValue()` total
///   into a [Double], mirroring [Reductions#sumGeneric(Array, Mask)]), the additive identity (`0`)
///   over zero selected non-null rows — and is reported as `null` for a non-numeric aggregate column
///   (the caller treats that as an unanswerable sum, mirroring the two-pass path where
///   [Reductions#requireNumeric(DType)] threw);
/// - `MIN` / `MAX` use the column's dtype-aware order ([Reductions#widenLong(Array, boolean, long)]
///   with unsigned-aware [Long#compareUnsigned(long, long)], [Double#compare(double, double)] for
///   floats, [Compare#values(Object, Object, DType)] otherwise) and are `null` when no selected row
///   is non-null.
///
/// The aggregate fold is type-specialized into boxing-free accumulators (a long domain, a double
/// domain) chosen once outside the loop; only the box at the end allocates. An aggregate without a
/// specialized lane (a non-primitive column, or an `f16` numeric primitive) folds its `SUM` and
/// `MIN` / `MAX` through the boxing [Values#valueAt(Array, long)] / [Compare] path so it stays
/// bit-identical to the two-pass reductions. Each filter leaf's [Predicate] is lowered once (reusing
/// [PrimitiveFilter#lowerLong(Predicate, long)] / [PrimitiveFilter#lowerDouble(Predicate)] so the
/// bounds can never drift from the standalone filter), with the typed accessor and validity hoisted
/// out of the row loop; a non-primitive or composite leaf falls back to the standalone filter's
/// per-element evaluator ([StreamingFilterKernel#evaluate(Array, long, Predicate)]).
final class FusedFilterAggregate {

    private FusedFilterAggregate() {
    }

    /// Evaluates `filter` over `chunk` and folds `aggColumn` over the rows it selects, in one pass.
    ///
    /// @param chunk     the decoded chunk holding the filter and aggregate columns
    /// @param filter    the whole boundary-chunk predicate: an n-ary `AND` of column-bound leaves, or
    ///                  a single column-bound leaf
    /// @param aggColumn the column to reduce over the selected rows, or `null` to count selected rows
    ///                  only (`COUNT(*)`)
    /// @return the fold over the selected rows: the selected row count, the aggregate's non-null count
    ///         among them, and (when `aggColumn` is given) its `SUM` / `MIN` / `MAX`
    static FilteredAggregate aggregate(Chunk chunk, RowFilter filter, String aggColumn) {
        long n = chunk.rowCount();
        List<RowPredicate> leafList = new ArrayList<>();
        collectLeaves(chunk, filter, leafList);
        RowPredicate[] leaves = leafList.toArray(RowPredicate[]::new);

        if (aggColumn == null) {
            long selected = 0L;
            for (long i = 0; i < n; i++) {
                if (allMatch(leaves, i)) {
                    selected++;
                }
            }
            return new FilteredAggregate(selected, 0L, null, null, null);
        }
        return foldAggregate(chunk.column(aggColumn), leaves, n);
    }

    /// Runs the row loop folding `agg` over the rows every leaf accepts, with the aggregate domain
    /// (long / double / generic) and its accessors hoisted out of the loop so the inner body is a bare
    /// typed read plus a primitive accumulate.
    ///
    /// @param agg    the aggregate column (possibly a [io.github.dfa1.vortex.reader.array.MaskedArray])
    /// @param leaves the lowered filter leaves, all of which must accept a row for it to be selected
    /// @param n      the chunk's row count
    /// @return the fold over the selected non-null aggregate rows
    private static FilteredAggregate foldAggregate(Array agg, RowPredicate[] leaves, long n) {
        Array aggData = Reductions.unwrap(agg);
        BoolArray aggValidity = Reductions.validityOf(agg);
        DType aggDtype = agg.dtype();
        boolean aggDoubleDomain = aggData instanceof DoubleArray || aggData instanceof FloatArray;
        boolean aggFloat = aggData instanceof FloatArray;
        boolean aggLongDomain = Reductions.isLongDomain(aggData);
        boolean aggUnsigned = aggLongDomain && aggData.dtype().isUnsigned();
        // A numeric primitive without a specialized long/double lane (an f16 column) still has an
        // answerable SUM: it folds a boxing doubleValue() total into a Double, mirroring
        // Reductions.sumGeneric. A non-numeric column (Utf8/Binary/Bool/…) has no sum and reports null.
        boolean aggNumericPrimitive = !aggLongDomain && !aggDoubleDomain && aggDtype instanceof DType.Primitive;

        long selected = 0L;
        long nonNull = 0L;
        long longSum = 0L;
        double doubleSum = 0.0;
        boolean found = false;
        long minLong = 0L;
        long maxLong = 0L;
        double minDouble = 0.0;
        double maxDouble = 0.0;
        Object minObject = null;
        Object maxObject = null;

        for (long i = 0; i < n; i++) {
            if (!allMatch(leaves, i)) {
                continue;
            }
            selected++;
            if (aggValidity != null && !aggValidity.getBoolean(i)) {
                continue;
            }
            nonNull++;
            if (aggLongDomain) {
                long v = Reductions.widenLong(aggData, aggUnsigned, i);
                longSum += v;
                if (!found) {
                    minLong = v;
                    maxLong = v;
                    found = true;
                } else {
                    if (lessLong(v, minLong, aggUnsigned)) {
                        minLong = v;
                    }
                    if (lessLong(maxLong, v, aggUnsigned)) {
                        maxLong = v;
                    }
                }
            } else if (aggDoubleDomain) {
                double v = aggFloat ? ((FloatArray) aggData).getFloat(i) : ((DoubleArray) aggData).getDouble(i);
                doubleSum += v;
                if (!found) {
                    minDouble = v;
                    maxDouble = v;
                    found = true;
                } else {
                    if (Double.compare(v, minDouble) < 0) {
                        minDouble = v;
                    }
                    if (Double.compare(v, maxDouble) > 0) {
                        maxDouble = v;
                    }
                }
            } else {
                Object v = Values.valueAt(agg, i);
                if (aggNumericPrimitive) {
                    doubleSum += ((Number) v).doubleValue();
                }
                if (minObject == null) {
                    minObject = v;
                    maxObject = v;
                } else {
                    if (Compare.values(v, minObject, aggDtype) < 0) {
                        minObject = v;
                    }
                    if (Compare.values(v, maxObject, aggDtype) > 0) {
                        maxObject = v;
                    }
                }
            }
        }

        // Explicit branches, not a ternary: a `Long : Double` conditional would binary-numeric promote
        // both arms to double and re-box the integer sum to Double, losing the long result type.
        Number sum;
        Object min;
        Object max;
        if (aggLongDomain) {
            sum = Long.valueOf(longSum);
            min = found ? Long.valueOf(minLong) : null;
            max = found ? Long.valueOf(maxLong) : null;
        } else if (aggDoubleDomain) {
            sum = Double.valueOf(doubleSum);
            min = boxDouble(found, minDouble, aggFloat);
            max = boxDouble(found, maxDouble, aggFloat);
        } else {
            // f16 (and any future numeric primitive without a specialized lane) sums as a Double via
            // the boxing doubleValue() fold, the additive identity (0.0) over zero non-null rows;
            // a genuinely non-numeric column (Utf8/Binary/Bool/…) has no answerable sum.
            sum = aggNumericPrimitive ? Double.valueOf(doubleSum) : null;
            min = minObject;
            max = maxObject;
        }
        return new FilteredAggregate(selected, nonNull, sum, min, max);
    }

    /// Reports whether `a` orders before `b` in the long domain, unsigned-aware so a `u64` value past
    /// the high bit compares as the larger magnitude.
    ///
    /// @param a        the left value (raw bits for the unsigned case)
    /// @param b        the right value
    /// @param unsigned whether the column is unsigned
    /// @return `true` if `a` is strictly less than `b` under the column's order
    private static boolean lessLong(long a, long b, boolean unsigned) {
        return unsigned ? Long.compareUnsigned(a, b) < 0 : a < b;
    }

    /// Boxes a double-domain extreme to the column's own accessor type — a [Float] for an `f32`
    /// column, a [Double] for `f64` — to stay bit-identical to [Reductions]' `MIN` / `MAX`, or `null`
    /// when no selected row was non-null.
    ///
    /// @param found    whether any selected row contributed a value
    /// @param value    the accumulated extreme
    /// @param isFloat  whether the column is an `f32` ([FloatArray])
    /// @return the boxed extreme, or `null` when `found` is `false`
    private static Object boxDouble(boolean found, double value, boolean isFloat) {
        if (!found) {
            return null;
        }
        // Kept as separate returns, not a `?:`: a Float/Double conditional triggers binary numeric
        // promotion (JLS 15.25), unboxing both branches to double and boxing an f32 column's extreme
        // back to Double — silently violating the Float contract above.
        if (isFloat) {
            // (float) round-trips a value that already came from a float accessor without loss.
            return Float.valueOf((float) value);
        }
        return Double.valueOf(value);
    }

    /// Reports whether every leaf accepts row `i` (the n-ary `AND`), short-circuiting on the first
    /// leaf that rejects it.
    ///
    /// @param leaves the lowered filter leaves
    /// @param i      the zero-based row
    /// @return `true` if every leaf accepts the row
    private static boolean allMatch(RowPredicate[] leaves, long i) {
        for (RowPredicate leaf : leaves) {
            if (!leaf.test(i)) {
                return false;
            }
        }
        return true;
    }

    /// Walks the filter tree, lowering each [RowFilter.Column] leaf into a hoisted [RowPredicate] over
    /// its decoded column and flattening the conjuncts of an n-ary `AND` into `out`.
    ///
    /// @param chunk  the chunk supplying each leaf's column
    /// @param filter the (sub-)filter to walk
    /// @param out    the collected per-leaf row predicates
    private static void collectLeaves(Chunk chunk, RowFilter filter, List<RowPredicate> out) {
        switch (filter) {
            case RowFilter.And(var parts) -> {
                for (RowFilter part : parts) {
                    collectLeaves(chunk, part, out);
                }
            }
            case RowFilter.Column(var column, var predicate) ->
                    out.add(leafTest(chunk.column(column), predicate));
        }
    }

    /// Lowers one column-bound [Predicate] into a hoisted [RowPredicate]: a null test reads validity
    /// directly, a numeric comparison leaf lowers once to the same range / operator the standalone
    /// filter uses, and any other shape falls back to the standalone per-element evaluator so the
    /// result is bit-identical to [Compute#filter(Array, Predicate, java.lang.foreign.Arena)].
    ///
    /// @param column    the decoded filter column (possibly masked)
    /// @param predicate the column-bound predicate
    /// @return a row predicate that tests `predicate` against `column` at a given row
    private static RowPredicate leafTest(Array column, Predicate predicate) {
        Array data = Reductions.unwrap(column);
        BoolArray validity = Reductions.validityOf(column);
        if (predicate instanceof Predicate.IsNull) {
            return validity == null ? i -> false : i -> !validity.getBoolean(i);
        }
        if (predicate instanceof Predicate.IsNotNull) {
            return validity == null ? i -> true : validity::getBoolean;
        }
        if (isComparisonLeaf(predicate) && PrimitiveFilter.predicateOk(predicate)
                && data.dtype() instanceof DType.Primitive) {
            if (data instanceof DoubleArray || data instanceof FloatArray) {
                return doubleLeaf(data, validity, predicate);
            }
            if (Reductions.isLongDomain(data)) {
                return longLeaf(data, validity, predicate);
            }
        }
        return i -> StreamingFilterKernel.evaluate(column, i, predicate);
    }

    /// Builds a hoisted long-domain comparison leaf: the predicate lowers once to an inclusive range
    /// on the order-preserving flipped longs, matching [PrimitiveFilter] exactly.
    ///
    /// @param data      the unwrapped long-domain filter array
    /// @param validity  the filter validity bitmap, or `null` when every row is valid
    /// @param predicate the comparison leaf
    /// @return the lowered row predicate
    private static RowPredicate longLeaf(Array data, BoolArray validity, Predicate predicate) {
        boolean unsigned = data.dtype().isUnsigned();
        long flip = unsigned ? Long.MIN_VALUE : 0L;
        PrimitiveFilter.LongRange range = PrimitiveFilter.lowerLong(predicate, flip);
        long lo = range.lo();
        long hi = range.hi();
        boolean negate = range.negate();
        if (validity == null) {
            return i -> {
                long v = Reductions.widenLong(data, unsigned, i) ^ flip;
                return (lo <= v && v <= hi) != negate;
            };
        }
        return i -> {
            if (!validity.getBoolean(i)) {
                return false;
            }
            long v = Reductions.widenLong(data, unsigned, i) ^ flip;
            return (lo <= v && v <= hi) != negate;
        };
    }

    /// Builds a hoisted double-domain comparison leaf: the predicate lowers once to its operator and
    /// bounds, compared with [Double#compare(double, double)] like [PrimitiveFilter].
    ///
    /// @param data      the unwrapped double-domain filter array
    /// @param validity  the filter validity bitmap, or `null` when every row is valid
    /// @param predicate the comparison leaf
    /// @return the lowered row predicate
    private static RowPredicate doubleLeaf(Array data, BoolArray validity, Predicate predicate) {
        PrimitiveFilter.DoubleBound bound = PrimitiveFilter.lowerDouble(predicate);
        PrimitiveFilter.DoubleOp op = bound.op();
        double lo = bound.lo();
        double hi = bound.hi();
        boolean isFloat = data instanceof FloatArray;
        if (validity == null) {
            return i -> PrimitiveFilter.matchDouble(readDouble(data, isFloat, i), op, lo, hi);
        }
        return i -> validity.getBoolean(i)
                && PrimitiveFilter.matchDouble(readDouble(data, isFloat, i), op, lo, hi);
    }

    /// Reads filter position `i` as a double through the hoisted typed accessor.
    ///
    /// @param data    the unwrapped double-domain filter array
    /// @param isFloat whether the column is an `f32` ([FloatArray]) rather than `f64`
    /// @param i       the zero-based position
    /// @return the value widened to a double
    private static double readDouble(Array data, boolean isFloat, long i) {
        return isFloat ? ((FloatArray) data).getFloat(i) : ((DoubleArray) data).getDouble(i);
    }

    /// Reports whether the predicate is a single value-comparison leaf the hoisted lane lowers — not a
    /// null test or a boolean composite, which the per-element evaluator handles.
    ///
    /// @param predicate the predicate to classify
    /// @return `true` for Eq / Neq / Lt / Gt / Lte / Gte / Between
    private static boolean isComparisonLeaf(Predicate predicate) {
        return predicate instanceof Predicate.Eq || predicate instanceof Predicate.Neq
                || predicate instanceof Predicate.Lt || predicate instanceof Predicate.Gt
                || predicate instanceof Predicate.Lte || predicate instanceof Predicate.Gte
                || predicate instanceof Predicate.Between;
    }

    /// A single filter leaf lowered to a boxing-free per-row test, hoisting its column accessor,
    /// validity, and lowered predicate bounds out of the kernel's row loop.
    @FunctionalInterface
    private interface RowPredicate {

        /// Reports whether the leaf accepts row `i`.
        ///
        /// @param i the zero-based row
        /// @return `true` if the leaf's column value at `i` satisfies the leaf's predicate
        boolean test(long i);
    }
}
