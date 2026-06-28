package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.array.Array;

import java.lang.foreign.Arena;
import java.util.Objects;

/// The minimal, low-level entry point to the compute primitives of ADR 0013 — filter an [Array] into
/// a selection [Mask] and reduce a masked [Array] to a scalar.
///
/// This is deliberately the *only* public surface: the kernels behind it stay package-private per
/// the ADR risk note, so early callers do not couple to a kernel shape that a future façade ADR
/// (transducer / Stream / fluent builder) will replace. There is no builder, no pipeline, no rich
/// API here — just the primitives.
///
/// Null handling follows the Rust reference: a filter excludes null positions (a null value makes a
/// value predicate false), and a reduce skips them — `SUM` and `COUNT` over zero selected non-null
/// rows return the identity (`0`), while `MIN` and `MAX` return `null`.
public final class Compute {

    private static final FilterKernel FILTER = new StreamingFilterKernel();

    private Compute() {
    }

    /// Filters `array` by `predicate`, returning the positions it accepts as a [Mask].
    ///
    /// Every position starts selected; the returned mask has the same length as `array` and selects
    /// exactly the positions whose value satisfies `predicate`. Null positions are excluded from a
    /// value predicate. The output bitmap is allocated off-heap from `arena`.
    ///
    /// @param array     the array to filter
    /// @param predicate the predicate to evaluate per position
    /// @param arena     the arena for the output bitmap; its [Arena#allocate(long)] zero-fills, which
    ///                  seeds the unselected bits to 0
    /// @return a mask of `array.length()` positions selected where `predicate` holds
    public static Mask filter(Array array, Predicate predicate, Arena arena) {
        Objects.requireNonNull(array, "array");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(arena, "arena");
        return FILTER.apply(array, Mask.allTrue(array.length()), predicate, arena);
    }

    /// Sums the selected non-null values of `array`.
    ///
    /// @param array the array to reduce
    /// @param mask  the selection mask, must have the same length as `array`
    /// @return the sum as a [Long] for integer columns or a [Double] for floating columns; the
    ///         additive identity (`0`) when no selected position is non-null
    public static Number sum(Array array, Mask mask) {
        return Reductions.SUM.apply(array, requireMask(array, mask));
    }

    /// Counts the selected non-null values of `array`.
    ///
    /// @param array the array to reduce
    /// @param mask  the selection mask, must have the same length as `array`
    /// @return the count of selected non-null values, `0` when none
    public static long count(Array array, Mask mask) {
        return Reductions.COUNT.apply(array, requireMask(array, mask));
    }

    /// Finds the smallest selected non-null value of `array` under its dtype-aware order.
    ///
    /// @param array the array to reduce
    /// @param mask  the selection mask, must have the same length as `array`
    /// @return the minimum value, or `null` when no selected position is non-null
    public static Object min(Array array, Mask mask) {
        return Reductions.MIN.apply(array, requireMask(array, mask));
    }

    /// Finds the largest selected non-null value of `array` under its dtype-aware order.
    ///
    /// @param array the array to reduce
    /// @param mask  the selection mask, must have the same length as `array`
    /// @return the maximum value, or `null` when no selected position is non-null
    public static Object max(Array array, Mask mask) {
        return Reductions.MAX.apply(array, requireMask(array, mask));
    }

    /// Validates the array and mask arguments shared by the reductions.
    ///
    /// @param array the array to reduce
    /// @param mask  the selection mask
    /// @return the validated mask
    private static Mask requireMask(Array array, Mask mask) {
        Objects.requireNonNull(array, "array");
        Objects.requireNonNull(mask, "mask");
        return mask;
    }
}
