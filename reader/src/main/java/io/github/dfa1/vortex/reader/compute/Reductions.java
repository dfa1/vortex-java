package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;

/// The concrete streaming [ReduceKernel] instances — `SUM`, `COUNT`, `MIN`, `MAX` — the tier-2
/// baseline of ADR 0013 §3 that folds any [Array] through the per-element accessor.
///
/// Value semantics mirror the reader's zone-map reductions (`ZoneReducer` / `ArrayStats`) so a
/// tier-2 residual reduce composes with a tier-1 whole-zone reduce: integer columns fold into a
/// [Long] (wrapping at 2^63, like SQL `SUM(BIGINT)`), floating columns into a [Double]. Nulls are
/// skipped throughout. The Rust empty/all-null results are mirrored exactly: `SUM` is the additive
/// identity (`0L` or `0.0`, never null), `COUNT` is `0`, and `MIN` / `MAX` are `null` (no non-null
/// value to report).
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

    /// Folds the selected non-null values into a sum, keyed off the column dtype.
    ///
    /// @param array   the array to fold
    /// @param current the selection mask
    /// @return the sum as a [Long] (integer columns) or [Double] (floating columns)
    /// @throws VortexException if the column dtype is not numeric (e.g. a Utf8 or Binary column)
    private static Number sum(Array array, Mask current) {
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
                // NOTE: boxing baseline (ADR 0013 §3 tier 2); wraps like ZoneReducer's long sum.
                acc += ((Number) Values.valueAt(array, i)).longValue();
            }
        }
        return acc;
    }

    /// Counts the selected non-null positions.
    ///
    /// @param array   the array to fold
    /// @param current the selection mask
    /// @return the count of non-null selected values
    private static Long count(Array array, Mask current) {
        long n = requireSameLength(array, current);
        long count = 0L;
        for (long i = 0; i < n; i++) {
            if (current.get(i) && !Values.isNullAt(array, i)) {
                count++;
            }
        }
        return count;
    }

    /// Finds the extreme selected non-null value under the column's dtype-aware order.
    ///
    /// @param array   the array to fold
    /// @param current the selection mask
    /// @param min     `true` for the minimum, `false` for the maximum
    /// @return the extreme value, or `null` if no selected position is non-null
    private static Object extremum(Array array, Mask current, boolean min) {
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

    /// Guards that `dtype` is a numeric primitive `SUM` can fold — a [DType.Primitive] (integer or
    /// floating). Any other column boxes to a value the long/double fold cannot total correctly: a
    /// non-numeric column (Utf8, Binary, Bool, …) boxes to a non-[Number], and a [DType.Decimal]
    /// boxes to a [BigDecimal] whose fraction the `longValue()` fold would silently truncate. Decimal
    /// `SUM` is deferred (matching the writer's zone-map `SUM`, which covers plain numeric primitives
    /// only); rejecting it here is correct, not a stop-gap. This turns what would be a raw
    /// [ClassCastException] (or a silent truncation) out of the public facade into the project's typed
    /// domain exception naming the bad dtype.
    ///
    /// @param dtype the column dtype to validate
    /// @throws VortexException if `dtype` is not a numeric primitive column
    private static void requireNumeric(DType dtype) {
        if (!(dtype instanceof DType.Primitive)) {
            throw new VortexException("compute: SUM is not supported on a non-numeric column of dtype "
                    + dtype);
        }
    }

    /// Reports whether the array's column dtype is a floating-point primitive.
    ///
    /// @param array the array to inspect
    /// @return `true` if the column is a floating-point primitive
    private static boolean isFloating(Array array) {
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
