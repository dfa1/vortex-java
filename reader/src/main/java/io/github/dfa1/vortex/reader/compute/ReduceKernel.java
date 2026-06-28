package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.array.Array;

/// A kernel that folds the selected positions of an [Array] into a single result, the reduce
/// signature sketched in ADR 0013 §2.
///
/// The kernel honours the current mask — only positions it selects contribute — and skips null
/// values, mirroring the Rust reduction semantics: a sum or count over zero selected non-null rows
/// is the identity, a min or max over zero selected non-null rows is absent.
///
/// Kept package-private per the ADR risk note: reductions are reached through the minimal [Compute]
/// entry point until a façade ADR settles the user-facing API. The concrete reductions live in
/// [Reductions].
///
/// @param <R> the result type of the reduction
interface ReduceKernel<R> {

    /// Folds the positions of `array` that `current` selects into a single result.
    ///
    /// @param array   the input array, possibly a lazy variant; never materialised whole
    /// @param current the selection mask, must have the same length as `array`
    /// @return the reduction result
    R apply(Array array, Mask current);
}
