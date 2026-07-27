package io.github.dfa1.vortex.core.model;

import java.util.Optional;
import java.util.Set;

/// A Vortex edition: a named, frozen (or draft) set of encodings that join its family at this
/// point, carrying a forever read-compatibility guarantee once frozen.
///
/// `added` is only the encodings that join *at this exact edition*, not the family's cumulative
/// set — [Editions#cumulativeMembers(Edition)] computes that.
///
/// The only `Edition` instances a caller should ever construct or pass to the writer module's
/// `WriteOptions#withEdition(Edition)` are [Editions]'s 8 catalog constants, mirroring the real,
/// frozen Vortex spec — an `Edition` fabricated with an invented member set would carry no actual
/// compatibility guarantee (nothing else in the ecosystem would recognize it). The canonical
/// constructor can't be restricted below `public` (a record's constructor can't be more
/// restrictive than the record type itself, and `Edition` must stay `public` since the writer
/// module — which depends on `core` but not vice versa — references it in its own public API), so
/// this is an API contract enforced by convention and documentation, not the compiler.
///
/// @param id               the edition identifier
/// @param minVortexVersion the minimum Vortex release whose reader supports every encoding in
///                         this edition, or empty if this edition is a draft (see [#isDraft()])
/// @param added            the encodings that join the family at this edition
public record Edition(EditionId id, Optional<String> minVortexVersion, Set<EncodingId> added) {

    /// Defensively copies `added` into an immutable set.
    public Edition {
        added = Set.copyOf(added);
    }

    /// An edition is a **draft** until its [#minVortexVersion] has been recorded — recording it is
    /// the act of freezing. A draft carries no read-compatibility guarantee and is never a default
    /// write target.
    ///
    /// @return `true` if this edition has no recorded `minVortexVersion`
    public boolean isDraft() {
        return minVortexVersion.isEmpty();
    }
}
