package io.github.dfa1.vortex.core.model;

import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Catalog of Vortex [Edition]s, mirroring the Rust reference's first-party declarations
/// (`vortex-edition/src/declarations/`, plus the plugin families declared in
/// `encodings/*/src/editions.rs`) at the release vortex-java interoperates with: vortex
/// 0.86.1, the pinned `vortex-jni` version. `EditionCatalogParityIntegrationTest` parses those
/// declarations and fails the build if this catalog drifts from them (issue #441).
///
/// Three families exist: `core` (frozen, the default writer target), `preview` (opt-in components
/// awaiting adoption into `core`; its one edition is still empty) and `zstd` (declared by Rust's
/// `vortex-zstd` plugin crate, not the core declarations; opt-in). Membership is
/// additive — an edition's full member set is the union of everything it and every earlier
/// edition of the same family added; see [#cumulativeMembers(Edition)].
///
/// Rust editions also gate layouts, extension dtypes and zone-map aggregates; this catalog models
/// array encodings only, so `core2026.08.0` (which adds only the `vortex.zoned` layout and the
/// zone-map aggregates) has no members here. Encodings in no edition at all — `fastlanes.delta`,
/// `vortex.patched` — are writable only with the guard turned off, as in Rust.
///
/// vortex-java implements every encoding in the catalog; `vortex.parquet.variant` and
/// `vortex.zstd_buffers` are read only.
public final class Editions {

    /// The baseline `core` edition: stable encodings writable by Vortex (Rust reference) 0.36.0.
    public static final Edition CORE_2025_05_0 = new Edition(
            new EditionId(EditionFamily.CORE, YearMonth.of(2025, 5), 0),
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
            Set.of(EncodingId.VORTEX_PCO, EncodingId.VORTEX_SEQUENCE, EncodingId.VORTEX_ZSTD));

    /// The `core` edition adding stable encodings released through October 2025.
    public static final Edition CORE_2025_10_0 = new Edition(
            new EditionId(EditionFamily.CORE, YearMonth.of(2025, 10), 0),
            Set.of(EncodingId.FASTLANES_RLE, EncodingId.VORTEX_FIXED_SIZE_LIST,
                    EncodingId.VORTEX_LISTVIEW, EncodingId.VORTEX_MASKED));

    /// The first August 2026 `core` edition. In Rust it adds the `vortex.zoned` layout and the
    /// zone-map aggregates, none of which this catalog models, so it has no encoding members.
    public static final Edition CORE_2026_08_0 = new Edition(
            new EditionId(EditionFamily.CORE, YearMonth.of(2026, 8), 0),
            Set.of());

    /// The second August 2026 `core` edition, adding OnPair string compression.
    public static final Edition CORE_2026_08_1 = new Edition(
            new EditionId(EditionFamily.CORE, YearMonth.of(2026, 8), 1),
            Set.of(EncodingId.VORTEX_ONPAIR));

    /// The third August 2026 `core` edition, adding the canonical Map encoding.
    public static final Edition CORE_2026_08_2 = new Edition(
            new EditionId(EditionFamily.CORE, YearMonth.of(2026, 8), 2),
            Set.of(EncodingId.VORTEX_MAP));

    /// The fourth August 2026 `core` edition, adding the Variant encodings (Rust also adds the
    /// `vortex.uuid` extension dtype here, which this catalog does not model). The newest frozen
    /// `core` edition, and the one the default writer targets, as in Rust.
    public static final Edition CORE_2026_08_3 = new Edition(
            new EditionId(EditionFamily.CORE, YearMonth.of(2026, 8), 3),
            Set.of(EncodingId.VORTEX_PARQUET_VARIANT, EncodingId.VORTEX_VARIANT));

    /// The August 2026 draft edition of the `preview` family. Empty in Rust too: no component has
    /// entered preview yet.
    public static final Edition PREVIEW_2026_08_0 = new Edition(
            new EditionId(EditionFamily.PREVIEW, YearMonth.of(2026, 8), 0),
            Set.of());

    /// The February 2026 draft edition of the `zstd` family, declared by Rust's `vortex-zstd` plugin:
    /// buffer-level Zstd that keeps an array's buffer layout for GPU decompression. vortex-java
    /// reads `vortex.zstd_buffers` but does not write it.
    public static final Edition ZSTD_2026_02_0 = new Edition(
            new EditionId(EditionFamily.ZSTD, YearMonth.of(2026, 2), 0),
            Set.of(EncodingId.VORTEX_ZSTD_BUFFERS));

    /// Every declared edition, in Rust's declaration order (core declarations first, then the
    /// plugin-declared families). Order matters:
    /// [#owningEdition(EncodingId)] returns the first entry whose `added` set contains the
    /// queried id.
    public static final List<Edition> ALL = List.of(
            CORE_2025_05_0, CORE_2025_06_0, CORE_2025_10_0, CORE_2026_08_0, CORE_2026_08_1,
            CORE_2026_08_2, CORE_2026_08_3, PREVIEW_2026_08_0, ZSTD_2026_02_0);

    private Editions() {
    }

    /// Computes `edition`'s full, cumulative member set: its own `added` encodings plus every
    /// encoding added by an earlier edition of the same family in [#ALL]. Seeding the result with
    /// `edition.added()` itself (rather than relying solely on a scan of [#ALL]) is what makes this
    /// correct for a caller-supplied `Edition` outside the catalog too, not just the built-ins —
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
