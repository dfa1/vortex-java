package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Golden tests pinning the edition data mirrored from Rust's `vortex-edition` declarations at
/// vortex 0.86.1: a stray edit to
/// [Editions]'s declarations that changes a cumulative member set fails here, mirroring upstream's
/// own `validate_edition` test harness.
class EditionsTest {

    // core2025.05.0's 23-member baseline, referenced by the typed EncodingId.WellKnown constants
    // (not the wire strings) so a typo here fails to compile instead of silently becoming an
    // unintended EncodingId.Custom.
    private static final Set<EncodingId> CORE_2025_05_0_BASELINE = Set.of(
            EncodingId.FASTLANES_BITPACKED, EncodingId.FASTLANES_FOR,
            EncodingId.VORTEX_ALP, EncodingId.VORTEX_ALPRD, EncodingId.VORTEX_BOOL,
            EncodingId.VORTEX_BYTEBOOL, EncodingId.VORTEX_CHUNKED, EncodingId.VORTEX_CONSTANT,
            EncodingId.VORTEX_DATETIMEPARTS, EncodingId.VORTEX_DECIMAL,
            EncodingId.VORTEX_DECIMAL_BYTE_PARTS, EncodingId.VORTEX_DICT, EncodingId.VORTEX_EXT,
            EncodingId.VORTEX_FSST, EncodingId.VORTEX_LIST, EncodingId.VORTEX_NULL,
            EncodingId.VORTEX_PRIMITIVE, EncodingId.VORTEX_RUNEND, EncodingId.VORTEX_SPARSE,
            EncodingId.VORTEX_STRUCT, EncodingId.VORTEX_VARBIN, EncodingId.VORTEX_VARBINVIEW,
            EncodingId.VORTEX_ZIGZAG);

    private static Set<EncodingId> union(Set<EncodingId> a, EncodingId... more) {
        Set<EncodingId> result = new LinkedHashSet<>(a);
        result.addAll(Set.of(more));
        return Set.copyOf(result);
    }

    @Nested
    class CumulativeMembers {

        @Test
        void core2025_05_0_isExactlyItsOwnBaseline() {
            // Given the first core edition — no earlier core edition to accumulate from
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.CORE_2025_05_0);

            // Then
            assertThat(result).isEqualTo(CORE_2025_05_0_BASELINE);
        }

        @Test
        void core2025_06_0_addsToTheBaseline() {
            // Given
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.CORE_2025_06_0);

            // Then — baseline 23 plus pco/sequence/zstd
            assertThat(result).isEqualTo(union(CORE_2025_05_0_BASELINE,
                    EncodingId.VORTEX_PCO, EncodingId.VORTEX_SEQUENCE, EncodingId.VORTEX_ZSTD));
        }

        @Test
        void core2025_10_0_addsToPreviousCore() {
            // Given
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.CORE_2025_10_0);

            // Then — the 26 above plus rle/fixed_size_list/listview/masked
            assertThat(result).isEqualTo(union(CORE_2025_05_0_BASELINE,
                    EncodingId.VORTEX_PCO, EncodingId.VORTEX_SEQUENCE, EncodingId.VORTEX_ZSTD,
                    EncodingId.FASTLANES_RLE, EncodingId.VORTEX_FIXED_SIZE_LIST,
                    EncodingId.VORTEX_LISTVIEW, EncodingId.VORTEX_MASKED));
        }

        @Test
        void core2026_08_0_addsNoEncoding() {
            // Given — Rust's core2026.08.0 adds only the vortex.zoned layout and zone-map
            // aggregates, which this catalog does not model
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.CORE_2026_08_0);

            // Then — exactly core2025.10.0's 30 members
            assertThat(result).hasSize(30).isEqualTo(Editions.cumulativeMembers(Editions.CORE_2025_10_0));
        }

        @Test
        void core2026_08_3_isTheFullCoreSet() {
            // Given — the newest frozen core edition, the default writer target
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.CORE_2026_08_3);

            // Then — the 30 above plus onpair (08.1), map (08.2) and the two variants (08.3); 34 total
            assertThat(result).hasSize(34).isEqualTo(union(CORE_2025_05_0_BASELINE,
                    EncodingId.VORTEX_PCO, EncodingId.VORTEX_SEQUENCE, EncodingId.VORTEX_ZSTD,
                    EncodingId.FASTLANES_RLE, EncodingId.VORTEX_FIXED_SIZE_LIST,
                    EncodingId.VORTEX_LISTVIEW, EncodingId.VORTEX_MASKED,
                    EncodingId.VORTEX_ONPAIR, EncodingId.VORTEX_MAP,
                    EncodingId.VORTEX_VARIANT, EncodingId.VORTEX_PARQUET_VARIANT));
        }

        @Test
        void preview2026_08_0_isEmpty() {
            // Given — no component has entered Rust's preview family yet
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.PREVIEW_2026_08_0);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        void editionNotInAll_cumulativeSeedsFromItsOwnAddedRatherThanRequiringIdentityInAll() {
            // Given — a hypothetical future core edition not (yet) declared in Editions.ALL.
            // Edition's constructor is package-private (only Editions' catalog constants exist
            // in production), so this exercises cumulativeMembers' seeding logic directly rather
            // than a reachable real-world scenario.
            Edition hypothetical = new Edition(
                    new EditionId(EditionFamily.CORE, YearMonth.of(2099, 1), 0),
                    Set.of(new EncodingId.Custom("vortex.future")));

            // When
            Set<EncodingId> result = Editions.cumulativeMembers(hypothetical);

            // Then — its own addition, plus one member from every earlier core edition
            assertThat(result).contains(
                    new EncodingId.Custom("vortex.future"), // its own addition
                    EncodingId.VORTEX_PRIMITIVE,             // core2025.05.0
                    EncodingId.VORTEX_ZSTD,                  // core2025.06.0
                    EncodingId.VORTEX_MASKED,                // core2025.10.0
                    EncodingId.VORTEX_VARIANT);              // core2026.08.3
        }
    }

    @Nested
    class OwningEdition {

        @Test
        void owningEdition_coreFamilyId_returnsTheEditionItFirstJoined() {
            // Given — vortex.zstd first joins at core2025.06.0, not the baseline
            // When
            Optional<Edition> result = Editions.owningEdition(EncodingId.VORTEX_ZSTD);

            // Then
            assertThat(result).contains(Editions.CORE_2025_06_0);
        }

        @Test
        void owningEdition_latestCoreFamilyId_returnsTheEditionItFirstJoined() {
            // Given — vortex.variant joins at the newest core edition, the last core entry scanned
            // When
            Optional<Edition> result = Editions.owningEdition(EncodingId.VORTEX_VARIANT);

            // Then
            assertThat(result).contains(Editions.CORE_2026_08_3);
        }

        @Test
        void owningEdition_onPair_isCoreNotADraft() {
            // Given — vortex.onpair joined Rust's core at 2026.08.1, so default writes may emit it
            // When
            Optional<Edition> result = Editions.owningEdition(EncodingId.VORTEX_ONPAIR);

            // Then
            assertThat(result).contains(Editions.CORE_2026_08_1);
        }

        @Test
        void owningEdition_zstdBuffers_isThePluginDeclaredZstdFamily() {
            // Given — vortex.zstd_buffers joins Rust's zstd family, declared by the vortex-zstd
            // plugin, not core (array-level vortex.zstd is core2025.06.0)
            // When
            Optional<Edition> result = Editions.owningEdition(EncodingId.VORTEX_ZSTD_BUFFERS);

            // Then
            assertThat(result).contains(Editions.ZSTD_2026_02_0);
            assertThat(result.get().id().family()).isEqualTo(EditionFamily.ZSTD);
        }

        @Test
        void owningEdition_encodingInNoEdition_returnsEmpty() {
            // Given — Rust declares fastlanes.delta and vortex.patched in no edition: only a write
            // with editions disabled may emit them
            // When / Then
            assertThat(Editions.owningEdition(EncodingId.FASTLANES_DELTA)).isEmpty();
            assertThat(Editions.owningEdition(EncodingId.VORTEX_PATCHED)).isEmpty();
        }

        @Test
        void owningEdition_idNotInAnyEdition_returnsEmpty() {
            // Given — a genuinely unknown, custom encoding no edition declares
            EncodingId unknown = new EncodingId.Custom("acme.widget");

            // When
            Optional<Edition> result = Editions.owningEdition(unknown);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
