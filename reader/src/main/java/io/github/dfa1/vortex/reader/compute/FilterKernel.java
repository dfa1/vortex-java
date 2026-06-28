package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.array.Array;

import java.lang.foreign.Arena;

/// A kernel that narrows a selection by evaluating a [Predicate] over an [Array], the filter
/// signature sketched in ADR 0013 §2.
///
/// The kernel receives the input array, the current selection mask, and the predicate; it returns a
/// new [Mask], of the same length as the array, with exactly the positions the predicate accepts
/// among those `current` already selects. Positional alignment is preserved so masks stay meaningful
/// across pipeline stages.
///
/// Deviation from the ADR §2 sketch (`Mask apply(A, Mask, Predicate)`): this interface adds a
/// trailing `Arena arena`. A [Mask.BitmapMask] result must back its bits with a
/// [java.lang.foreign.MemorySegment], and the project memory rule forbids `new byte[]` for kernel
/// output — the bitmap is allocated off-heap from the caller's [Arena] (the reader's confined scan
/// arena). The parameter is an [Arena] rather than a plain [java.lang.foreign.SegmentAllocator] on
/// purpose: the kernel writes only the selected bits and relies on every other bit already being
/// zero, a guarantee only [Arena#allocate(long)] makes (it zero-fills per the FFM spec); a bare
/// `SegmentAllocator` makes no such promise.
///
/// Kept package-private per the ADR risk note: the kernels stay hidden behind the minimal [Compute]
/// entry point until a façade ADR settles the user-facing API.
interface FilterKernel {

    /// Evaluates `predicate` over `array`, keeping only positions `current` already selects.
    ///
    /// @param array     the input array, possibly a lazy variant; never materialised whole
    /// @param current   the incoming selection mask, must have the same length as `array`
    /// @param predicate the predicate to evaluate per position
    /// @param arena     the arena for the output bitmap; its [Arena#allocate(long)] zero-fills, which
    ///                  seeds the unselected bits to 0
    /// @return a mask of `array.length()` positions, selected where `current` and `predicate` agree
    Mask apply(Array array, Mask current, Predicate predicate, Arena arena);
}
