package io.github.dfa1.vortex.core.model;

/// The Vortex edition families.
///
/// Unlike [EncodingId]/[LayoutId], this is a closed enum, not a sealed
/// interface with a `Custom` fallback: an edition family is a cross-implementation
/// compatibility promise ("any reader supporting `core2026.07.0` can read this file"), so a
/// private, single-writer "family" would carry no real guarantee — nothing else in the Vortex
/// ecosystem would recognize it. There is no legitimate use case for a fabricated family, so
/// none is offered.
///
/// Deliberately does not override `toString()`: an enum's `toString()` returning anything other
/// than its declared constant name is surprising (it breaks the usual assumption that
/// `enum.toString().equals(enum.name())`, and would make `valueOf(family.toString())` fail).
/// [EditionId#toString()] is the one place that needs the lowercase wire form and computes it
/// explicitly.
public enum EditionFamily {
    /// The `core` family: encodings the default writer emits, each edition frozen with a forever
    /// read-compatibility guarantee.
    CORE,
    /// The `unstable` family: opt-in encodings with no compatibility guarantee — every `unstable`
    /// edition is a draft.
    UNSTABLE
}
