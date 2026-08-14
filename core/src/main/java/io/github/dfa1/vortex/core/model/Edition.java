package io.github.dfa1.vortex.core.model;

import java.util.Set;

/// A Vortex edition: a named set of encodings that join its family at this point. A `core`-family
/// edition is frozen and carries a forever read-compatibility guarantee; an `unstable`-family
/// edition is a draft and carries none — see [EditionFamily].
///
/// `added` is only the encodings that join *at this exact edition*, not the family's cumulative
/// set — [Editions#cumulativeMembers(Edition)] computes that.
///
/// Deliberately does not carry the upstream Rust spec's `min_vortex_version` (the earliest
/// *Rust* Vortex release whose reader supports the edition): that version number refers to a
/// different implementation's release train and has no relationship to vortex-java's own
/// versioning, so surfacing it in a vortex-java message or doc would read as (and be) meaningless
/// for vortex-java's own users.
///
/// The only `Edition` instances a caller should ever construct or pass to the writer module's
/// `WriteOptions#withEdition(Edition)` are [Editions]'s catalog constants, mirroring the real,
/// frozen Vortex spec — an `Edition` fabricated with an invented member set would carry no actual
/// compatibility guarantee (nothing else in the ecosystem would recognize it). The canonical
/// constructor can't be restricted below `public` (a record's constructor can't be more
/// restrictive than the record type itself, and `Edition` must stay `public` since the writer
/// module — which depends on `core` but not vice versa — references it in its own public API), so
/// this is an API contract enforced by convention and documentation, not the compiler.
///
/// @param id    the edition identifier
/// @param added the encodings that join the family at this edition
public record Edition(EditionId id, Set<EncodingId> added) {

    /// Defensively copies `added` into an immutable set.
    public Edition {
        added = Set.copyOf(added);
    }
}
