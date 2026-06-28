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
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;

/// The concrete streaming [ReduceKernel] instances — `SUM`, `COUNT`, `MIN`, `MAX` — of ADR 0013 §3.
///
/// Each reduction has a type-specialized fast lane (a boxing-free primitive accumulation loop) and a
/// generic boxing fallback (`*Generic`) for the types the fast lane does not cover (Decimal, Utf8,
/// `f16`, …). The specialized lane unwraps a [MaskedArray] once, hoists the column's signedness and
/// the validity / all-true-mask decisions out of the per-row loop (the hot-loop rule's branch-split
/// idiom), and reads each element through the concrete typed accessor — no [Object] box, no
/// [Comparable#compareTo(Object)], no autobox. The result is boxed once at the end.
///
/// Value semantics mirror the reader's zone-map reductions (`ZoneReducer` / `ArrayStats`): integer
/// columns fold into a [Long] (wrapping at 2^63, like SQL `SUM(BIGINT)`), floating columns into a
/// [Double], nulls are skipped, and the Rust empty / all-null results are mirrored exactly — `SUM` is
/// the additive identity (`0L` or `0.0`, never null), `COUNT` is `0`, and `MIN` / `MAX` are `null`.
// transitional — every reduction consumes the deprecated Mask until the fused multi-column filteredReduce lands
@SuppressWarnings("removal")
final class Reductions {

    /// Sums the selected non-null values: a [Long] for integer columns, a [Double] for floats.
    /// Empty or all-null input folds to the additive identity (`0L` / `0.0`), never null.
    static final ReduceKernel<Number> SUM = Reductions::sum;

    /// Counts the selected non-null values as a [Long]. Empty input is `0`.
    static final ReduceKernel<Long> COUNT = Reductions::count;

    /// The smallest selected non-null value under the column's dtype-aware order, or `null` when no
    /// selected position is non-null.
    static final ReduceKernel<Object> MIN = (array, current) -> extremum(array, current, true);

    /// The largest selected non-null value under the column's dtype-aware order, or `null` when no
    /// selected position is non-null.
    static final ReduceKernel<Object> MAX = (array, current) -> extremum(array, current, false);

    private Reductions() {
    }

    /// Sums the selected non-null values, taking the specialized primitive lane when possible.
    ///
    /// @param array   the array to fold
    /// @param current the selection mask
    /// @return the sum as a [Long] (integer columns) or [Double] (floating columns)
    /// @throws VortexException if the column dtype is not numeric (e.g. a Utf8 or Binary column)
    private static Number sum(Array array, Mask current) {
        requireNumeric(array.dtype());
        long n = requireSameLength(array, current);
        Array data = unwrap(array);
        BoolArray validity = validityOf(array);
        if (data instanceof DoubleArray || data instanceof FloatArray) {
            return sumDoubleDomain(data, validity, current, n);
        }
        if (isLongDomain(data)) {
            return sumLongDomain(data, data.dtype().isUnsigned(), validity, current, n);
        }
        // f16 and any other numeric primitive without a specialized accessor stay on the boxing path.
        return sumGeneric(array, current);
    }

    /// Counts the selected non-null positions. Works for every array type without boxing — it reads
    /// only the validity bitmap and the mask, never a value.
    ///
    /// @param array   the array to fold
    /// @param current the selection mask
    /// @return the count of non-null selected values
    private static Long count(Array array, Mask current) {
        long n = requireSameLength(array, current);
        BoolArray validity = validityOf(array);
        if (validity == null) {
            // No nulls: the count of selected positions is exactly the mask's true count.
            return current.trueCount();
        }
        boolean allTrue = current instanceof Mask.AllTrue;
        long count = 0L;
        for (long i = 0; i < n; i++) {
            if ((allTrue || current.get(i)) && validity.getBoolean(i)) {
                count++;
            }
        }
        return count;
    }

    /// Finds the extreme selected non-null value, taking the specialized primitive lane when possible.
    ///
    /// @param array   the array to fold
    /// @param current the selection mask
    /// @param min     `true` for the minimum, `false` for the maximum
    /// @return the extreme value, or `null` if no selected position is non-null
    private static Object extremum(Array array, Mask current, boolean min) {
        long n = requireSameLength(array, current);
        Array data = unwrap(array);
        BoolArray validity = validityOf(array);
        if (data instanceof DoubleArray || data instanceof FloatArray) {
            return extremumDoubleDomain(data, validity, current, n, min);
        }
        if (isLongDomain(data)) {
            return extremumLongDomain(data, data.dtype().isUnsigned(), validity, current, n, min);
        }
        return extremumGeneric(array, current, min);
    }

    // ----------------------------------------------------------------------------------------------
    // Specialized long-domain lanes.
    // ----------------------------------------------------------------------------------------------

    /// Sums a long-domain column. The hot path (no nulls, all-true mask) is a tight monomorphic loop;
    /// the gated path applies validity and mask through short-circuited loop-invariant flags.
    ///
    /// @param data     the unwrapped long-domain array
    /// @param unsigned whether the column is unsigned (narrow types zero-extend before the add)
    /// @param validity the validity bitmap, or `null` when every position is valid
    /// @param current  the selection mask
    /// @param n        the row count
    /// @return the wrapping integer sum boxed as a [Long]
    private static Long sumLongDomain(Array data, boolean unsigned, BoolArray validity, Mask current, long n) {
        boolean noNull = validity == null;
        boolean allTrue = current instanceof Mask.AllTrue;
        long acc = 0L;
        if (data instanceof LongArray la) {
            // No-box fast lane: raw 64-bit add (signed and unsigned sums share the same bits).
            for (long i = 0; i < n; i++) {
                if (included(noNull, validity, allTrue, current, i)) {
                    acc += la.getLong(i);
                }
            }
        } else if (data instanceof IntArray ia) {
            for (long i = 0; i < n; i++) {
                if (included(noNull, validity, allTrue, current, i)) {
                    acc += unsigned ? (ia.getInt(i) & 0xFFFFFFFFL) : ia.getInt(i);
                }
            }
        } else if (data instanceof ShortArray sa) {
            for (long i = 0; i < n; i++) {
                if (included(noNull, validity, allTrue, current, i)) {
                    acc += unsigned ? (sa.getShort(i) & 0xFFFFL) : sa.getShort(i);
                }
            }
        } else {
            ByteArray ba = (ByteArray) data;
            for (long i = 0; i < n; i++) {
                if (included(noNull, validity, allTrue, current, i)) {
                    acc += unsigned ? (ba.getByte(i) & 0xFFL) : ba.getByte(i);
                }
            }
        }
        return acc;
    }

    /// Finds the extreme of a long-domain column under the dtype-aware (signed / unsigned) order.
    ///
    /// @param data     the unwrapped long-domain array
    /// @param unsigned whether the column is unsigned (drives both the widening and the comparison)
    /// @param validity the validity bitmap, or `null` when every position is valid
    /// @param current  the selection mask
    /// @param n        the row count
    /// @param min      `true` for the minimum, `false` for the maximum
    /// @return the extreme value boxed as a [Long], or `null` when nothing is selected and non-null
    private static Object extremumLongDomain(Array data, boolean unsigned, BoolArray validity, Mask current,
                                             long n, boolean min) {
        boolean noNull = validity == null;
        boolean allTrue = current instanceof Mask.AllTrue;
        boolean found = false;
        long best = 0L;
        for (long i = 0; i < n; i++) {
            if (!included(noNull, validity, allTrue, current, i)) {
                continue;
            }
            long v = widenLong(data, unsigned, i);
            if (!found) {
                best = v;
                found = true;
            } else {
                int cmp = unsigned ? Long.compareUnsigned(v, best) : Long.compare(v, best);
                if (min ? cmp < 0 : cmp > 0) {
                    best = v;
                }
            }
        }
        return found ? Long.valueOf(best) : null;
    }

    /// Reads element `i` of a long-domain array widened to a `long`, zero-extending narrow unsigned
    /// columns exactly as [Values#valueAt(Array, long)] boxes them.
    ///
    /// @param data     the unwrapped long-domain array
    /// @param unsigned whether the column is unsigned
    /// @param i        the zero-based position
    /// @return the widened element value
    static long widenLong(Array data, boolean unsigned, long i) {
        if (data instanceof LongArray la) {
            return la.getLong(i);
        }
        if (data instanceof IntArray ia) {
            return unsigned ? (ia.getInt(i) & 0xFFFFFFFFL) : ia.getInt(i);
        }
        if (data instanceof ShortArray sa) {
            return unsigned ? (sa.getShort(i) & 0xFFFFL) : sa.getShort(i);
        }
        ByteArray ba = (ByteArray) data;
        return unsigned ? (ba.getByte(i) & 0xFFL) : ba.getByte(i);
    }

    // ----------------------------------------------------------------------------------------------
    // Specialized double-domain lanes.
    // ----------------------------------------------------------------------------------------------

    /// Sums a double-domain column into a [Double], skipping nulls and unselected positions.
    ///
    /// @param data     the unwrapped double-domain array
    /// @param validity the validity bitmap, or `null` when every position is valid
    /// @param current  the selection mask
    /// @param n        the row count
    /// @return the sum boxed as a [Double]
    private static Double sumDoubleDomain(Array data, BoolArray validity, Mask current, long n) {
        boolean noNull = validity == null;
        boolean allTrue = current instanceof Mask.AllTrue;
        double acc = 0.0;
        if (data instanceof DoubleArray da) {
            for (long i = 0; i < n; i++) {
                if (included(noNull, validity, allTrue, current, i)) {
                    acc += da.getDouble(i);
                }
            }
        } else {
            FloatArray fa = (FloatArray) data;
            for (long i = 0; i < n; i++) {
                if (included(noNull, validity, allTrue, current, i)) {
                    acc += fa.getFloat(i);
                }
            }
        }
        return acc;
    }

    /// Finds the extreme of a double-domain column under [Double#compare(double, double)] order, so
    /// NaN and signed-zero ordering matches the generic path. The result is boxed to the column's own
    /// accessor type — a [Float] for an `f32` column, a [Double] for `f64` — to stay bit-identical to
    /// the boxing path (whose `MIN` / `MAX` echoes the element box, unlike `SUM` which is always a
    /// [Double]).
    ///
    /// @param data     the unwrapped double-domain array
    /// @param validity the validity bitmap, or `null` when every position is valid
    /// @param current  the selection mask
    /// @param n        the row count
    /// @param min      `true` for the minimum, `false` for the maximum
    /// @return the extreme value boxed as a [Float] / [Double], or `null` when nothing is selected
    private static Object extremumDoubleDomain(Array data, BoolArray validity, Mask current, long n,
                                               boolean min) {
        boolean noNull = validity == null;
        boolean allTrue = current instanceof Mask.AllTrue;
        boolean isFloat = data instanceof FloatArray;
        boolean found = false;
        double best = 0.0;
        for (long i = 0; i < n; i++) {
            if (!included(noNull, validity, allTrue, current, i)) {
                continue;
            }
            double v = isFloat ? ((FloatArray) data).getFloat(i) : ((DoubleArray) data).getDouble(i);
            if (!found) {
                best = v;
                found = true;
            } else {
                int cmp = Double.compare(v, best);
                if (min ? cmp < 0 : cmp > 0) {
                    best = v;
                }
            }
        }
        if (!found) {
            return null;
        }
        // Explicit branches, not a ternary: a `Float ? : Double` conditional would binary-numeric
        // promote both arms to double and re-box to Double, losing the float result type.
        if (isFloat) {
            // (float) round-trips a value that already came from a float accessor without loss.
            return Float.valueOf((float) best);
        }
        return Double.valueOf(best);
    }

    /// Reports whether a selected, non-null position contributes, with the no-null and all-true-mask
    /// flags hoisted so the hot path short-circuits both reads.
    ///
    /// @param noNull   `true` when the column has no validity bitmap
    /// @param validity the validity bitmap, read only when `noNull` is `false`
    /// @param allTrue  `true` when every position is selected
    /// @param current  the selection mask, read only when `allTrue` is `false`
    /// @param i        the zero-based position
    /// @return `true` if position `i` is both valid and selected
    private static boolean included(boolean noNull, BoolArray validity, boolean allTrue, Mask current, long i) {
        return (noNull || validity.getBoolean(i)) && (allTrue || current.get(i));
    }

    // ----------------------------------------------------------------------------------------------
    // Generic boxing oracles — the fallback path and the equivalence-test reference.
    // ----------------------------------------------------------------------------------------------

    /// Folds the selected non-null values into a sum through the boxing accessor — the fallback for
    /// types without a specialized lane and the oracle the fast lane is tested against.
    ///
    /// @param array   the array to fold
    /// @param current the selection mask
    /// @return the sum as a [Long] (integer columns) or [Double] (floating columns)
    /// @throws VortexException if the column dtype is not numeric
    static Number sumGeneric(Array array, Mask current) {
        requireNumeric(array.dtype());
        long n = requireSameLength(array, current);
        if (isFloating(array)) {
            double acc = 0.0;
            for (long i = 0; i < n; i++) {
                if (current.get(i) && !Values.isNullAt(array, i)) {
                    acc += ((Number) Values.valueAt(array, i)).doubleValue();
                }
            }
            return acc;
        }
        long acc = 0L;
        for (long i = 0; i < n; i++) {
            if (current.get(i) && !Values.isNullAt(array, i)) {
                acc += ((Number) Values.valueAt(array, i)).longValue();
            }
        }
        return acc;
    }

    /// Counts the selected non-null positions through the boxing-free generic path.
    ///
    /// @param array   the array to fold
    /// @param current the selection mask
    /// @return the count of non-null selected values
    static Long countGeneric(Array array, Mask current) {
        long n = requireSameLength(array, current);
        long count = 0L;
        for (long i = 0; i < n; i++) {
            if (current.get(i) && !Values.isNullAt(array, i)) {
                count++;
            }
        }
        return count;
    }

    /// Finds the extreme selected non-null value through the boxing accessor — the fallback for types
    /// without a specialized lane and the oracle the fast lane is tested against.
    ///
    /// @param array   the array to fold
    /// @param current the selection mask
    /// @param min     `true` for the minimum, `false` for the maximum
    /// @return the extreme value, or `null` if no selected position is non-null
    static Object extremumGeneric(Array array, Mask current, boolean min) {
        long n = requireSameLength(array, current);
        DType dtype = array.dtype();
        Object best = null;
        for (long i = 0; i < n; i++) {
            if (!current.get(i) || Values.isNullAt(array, i)) {
                continue;
            }
            Object value = Values.valueAt(array, i);
            if (best == null) {
                best = value;
            } else {
                int order = Compare.values(value, best, dtype);
                boolean replace = min ? order < 0 : order > 0;
                if (replace) {
                    best = value;
                }
            }
        }
        return best;
    }

    // ----------------------------------------------------------------------------------------------
    // Shared helpers.
    // ----------------------------------------------------------------------------------------------

    /// Unwraps a [MaskedArray] to its non-nullable payload, or returns the array unchanged.
    ///
    /// @param array the array to unwrap
    /// @return the underlying value array
    static Array unwrap(Array array) {
        return array instanceof MaskedArray masked ? masked.inner() : array;
    }

    /// Returns the validity bitmap of a [MaskedArray], or `null` when the array carries no nulls.
    ///
    /// @param array the array to inspect
    /// @return the validity bitmap, or `null`
    static BoolArray validityOf(Array array) {
        return array instanceof MaskedArray masked ? masked.validity() : null;
    }

    /// Reports whether the unwrapped array is a specialized long-domain primitive.
    ///
    /// @param data the unwrapped array
    /// @return `true` for [LongArray] / [IntArray] / [ShortArray] / [ByteArray]
    static boolean isLongDomain(Array data) {
        return data instanceof LongArray || data instanceof IntArray
                || data instanceof ShortArray || data instanceof ByteArray;
    }

    /// Guards that `dtype` is a numeric primitive `SUM` can fold — a [DType.Primitive] (integer or
    /// floating). Any other column boxes to a value the long/double fold cannot total correctly: a
    /// non-numeric column (Utf8, Binary, Bool, …) boxes to a non-[Number], and a [DType.Decimal]
    /// boxes to a [java.math.BigDecimal] whose fraction the `longValue()` fold would silently
    /// truncate. Decimal `SUM` is deferred (matching the writer's zone-map `SUM`, which covers plain
    /// numeric primitives only); rejecting it here is correct, not a stop-gap.
    ///
    /// @param dtype the column dtype to validate
    /// @throws VortexException if `dtype` is not a numeric primitive column
    static void requireNumeric(DType dtype) {
        if (!(dtype instanceof DType.Primitive)) {
            throw new VortexException("compute: SUM is not supported on a non-numeric column of dtype "
                    + dtype);
        }
    }

    /// Reports whether the array's column dtype is a floating-point primitive.
    ///
    /// @param array the array to inspect
    /// @return `true` if the column is a floating-point primitive
    static boolean isFloating(Array array) {
        return array.dtype() instanceof DType.Primitive prim && prim.ptype().isFloating();
    }

    /// Validates that the mask covers exactly the array's positions, returning the shared length.
    ///
    /// @param array   the array being reduced
    /// @param current the selection mask
    /// @return the common length
    /// @throws VortexException if the mask length differs from the array length
    private static long requireSameLength(Array array, Mask current) {
        long n = array.length();
        if (current.length() != n) {
            throw new VortexException("reduce: mask length " + current.length()
                    + " != array length " + n);
        }
        return n;
    }
}
