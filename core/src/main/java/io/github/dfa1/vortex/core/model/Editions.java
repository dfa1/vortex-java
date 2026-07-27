package io.github.dfa1.vortex.core.model;

import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Catalog of Vortex [Edition]s, seeded from the ground-truth declarations in
/// [vortex-data/vortex#8871](https://github.com/vortex-data/vortex/pull/8871) (the published
/// [editions spec](https://github.com/vortex-data/vortex/blob/develop/docs/specs/editions.md)'s
/// own "Edition registry" section is not yet populated upstream, so this catalog was built
/// directly from the Rust source: `vortex-edition/src/lib.rs` and
/// `vortex/src/editions/{core,unstable}/*.rs`).
///
/// Two families exist today: `core` (frozen, the default writer target) and `unstable` (draft,
/// opt-in, no compatibility guarantee). Membership is additive — an edition's full member set is
/// the union of everything it and every earlier edition of the same family added; see
/// [#cumulativeMembers(Edition)].
///
/// vortex-java implements every `core`-family encoding through `core2026.07.0`, referenced below
/// by their [EncodingId.WellKnown] constants. Of `unstable`, it implements only `fastlanes.delta`
/// and `vortex.patched` — the remaining ids (`vortex.zstd_buffers`, `vortex.parquet.variant`, the
/// `vortex.tensor.*` family, `vortex.onpair`) have no `WellKnown` constant yet, so they are named
/// as [EncodingId.Custom] instead; the catalog stores both uniformly and mirrors upstream
/// faithfully rather than being truncated to what is implemented today.
public final class Editions {

    /// The baseline `core` edition: stable encodings writable by Vortex 0.36.0.
    public static final Edition CORE_2025_05_0 = new Edition(
            new EditionId(EditionFamily.CORE, YearMonth.of(2025, 5), 0),
            Optional.of("0.36.0"),
            Set.of(
                    EncodingId.FASTLANES_BITPACKED, EncodingId.FASTLANES_FOR,
                    EncodingId.VORTEX_ALP, EncodingId.VORTEX_ALPRD, EncodingId.VORTEX_BOOL,
                    EncodingId.VORTEX_BYTEBOOL, EncodingId.VORTEX_CHUNKED, EncodingId.VORTEX_CONSTANT,
                    EncodingId.VORTEX_DATETIMEPARTS, EncodingId.VORTEX_DECIMAL,
                    EncodingId.VORTEX_DECIMAL_BYTE_PARTS, EncodingId.VORTEX_DICT, EncodingId.VORTEX_EXT,
                    EncodingId.VORTEX_FSST, EncodingId.VORTEX_LIST, EncodingId.VORTEX_NULL,
                    EncodingId.VORTEX_PRIMITIVE, EncodingId.VORTEX_RUNEND, EncodingId.VORTEX_SPARSE,
                    EncodingId.VORTEX_STRUCT, EncodingId.VORTEX_VARBIN, EncodingId.VORTEX_VARBINVIEW,
                    EncodingId.VORTEX_ZIGZAG));

    /// The `core` edition adding stable encodings released through June 2025.
    public static final Edition CORE_2025_06_0 = new Edition(
            new EditionId(EditionFamily.CORE, YearMonth.of(2025, 6), 0),
            Optional.of("0.40.0"),
            Set.of(EncodingId.VORTEX_PCO, EncodingId.VORTEX_SEQUENCE, EncodingId.VORTEX_ZSTD));

    /// The `core` edition adding stable encodings released through October 2025.
    public static final Edition CORE_2025_10_0 = new Edition(
            new EditionId(EditionFamily.CORE, YearMonth.of(2025, 10), 0),
            Optional.of("0.54.0"),
            Set.of(EncodingId.FASTLANES_RLE, EncodingId.VORTEX_FIXED_SIZE_LIST,
                    EncodingId.VORTEX_LISTVIEW, EncodingId.VORTEX_MASKED));

    /// The `core` edition adding stable encodings released through July 2026.
    public static final Edition CORE_2026_07_0 = new Edition(
            new EditionId(EditionFamily.CORE, YearMonth.of(2026, 7), 0),
            Optional.of("0.65.0"),
            Set.of(EncodingId.VORTEX_VARIANT));

    /// The May 2025 draft edition of the `unstable` family.
    public static final Edition UNSTABLE_2025_05_0 = new Edition(
            new EditionId(EditionFamily.UNSTABLE, YearMonth.of(2025, 5), 0),
            Optional.empty(),
            Set.of(EncodingId.FASTLANES_DELTA));

    /// The February 2026 draft edition of the `unstable` family.
    public static final Edition UNSTABLE_2026_02_0 = new Edition(
            new EditionId(EditionFamily.UNSTABLE, YearMonth.of(2026, 2), 0),
            Optional.empty(),
            Set.of(new EncodingId.Custom("vortex.zstd_buffers")));

    /// The April 2026 draft edition of the `unstable` family.
    public static final Edition UNSTABLE_2026_04_0 = new Edition(
            new EditionId(EditionFamily.UNSTABLE, YearMonth.of(2026, 4), 0),
            Optional.empty(),
            Set.of(
                    new EncodingId.Custom("vortex.parquet.variant"), EncodingId.VORTEX_PATCHED,
                    new EncodingId.Custom("vortex.tensor.cosine_similarity"),
                    new EncodingId.Custom("vortex.tensor.inner_product"),
                    new EncodingId.Custom("vortex.tensor.l2_denorm"),
                    new EncodingId.Custom("vortex.tensor.l2_norm")));

    /// The June 2026 draft edition of the `unstable` family.
    public static final Edition UNSTABLE_2026_06_0 = new Edition(
            new EditionId(EditionFamily.UNSTABLE, YearMonth.of(2026, 6), 0),
            Optional.empty(),
            Set.of(new EncodingId.Custom("vortex.onpair")));

    /// Every declared edition, in the order above. Order matters: [#owningEdition(EncodingId)]
    /// returns the first entry whose `added` set contains the queried id.
    public static final List<Edition> ALL = List.of(
            CORE_2025_05_0, CORE_2025_06_0, CORE_2025_10_0, CORE_2026_07_0,
            UNSTABLE_2025_05_0, UNSTABLE_2026_02_0, UNSTABLE_2026_04_0, UNSTABLE_2026_06_0);

    private Editions() {
    }

    /// Computes `edition`'s full, cumulative member set: its own `added` encodings plus every
    /// encoding added by an earlier edition of the same family in [#ALL]. Seeding the result with
    /// `edition.added()` itself (rather than relying solely on a scan of [#ALL]) is what makes this
    /// correct for a caller-supplied `Edition` outside the catalog too, not just the 8 built-ins —
    /// such a custom edition has no known earlier edition to accumulate from, so its cumulative set
    /// is exactly its own.
    ///
    /// @param edition the edition whose cumulative membership to compute
    /// @return the union of `edition`'s own additions and every earlier same-family edition's
    public static Set<EncodingId> cumulativeMembers(Edition edition) {
        Set<EncodingId> result = new LinkedHashSet<>(edition.added());
        for (Edition candidate : ALL) {
            if (candidate.id().family().equals(edition.id().family())
                    && candidate.id().isAtOrBefore(edition.id())) {
                result.addAll(candidate.added());
            }
        }
        return Set.copyOf(result);
    }

    /// Returns the edition `id` first joined, if it is part of any declared edition.
    ///
    /// Correct because membership is additive and each encoding belongs to exactly one family:
    /// the first entry in [#ALL] whose `added` set contains `id` is unambiguously the edition it
    /// joined at.
    ///
    /// @param id the encoding id to look up
    /// @return the edition `id` first joined, or empty if `id` is not part of any declared edition
    public static Optional<Edition> owningEdition(EncodingId id) {
        for (Edition candidate : ALL) {
            if (candidate.added().contains(id)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
