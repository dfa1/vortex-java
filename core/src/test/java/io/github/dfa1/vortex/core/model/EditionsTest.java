package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Golden tests pinning the ground-truth edition data from
/// [vortex-data/vortex#8871](https://github.com/vortex-data/vortex/pull/8871): a stray edit to
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
        void core2026_07_0_isTheFullCoreSet() {
            // Given — the latest frozen core edition
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.CORE_2026_07_0);

            // Then — every core encoding through vortex.variant; 31 total
            assertThat(result).hasSize(31).isEqualTo(union(CORE_2025_05_0_BASELINE,
                    EncodingId.VORTEX_PCO, EncodingId.VORTEX_SEQUENCE, EncodingId.VORTEX_ZSTD,
                    EncodingId.FASTLANES_RLE, EncodingId.VORTEX_FIXED_SIZE_LIST,
                    EncodingId.VORTEX_LISTVIEW, EncodingId.VORTEX_MASKED,
                    EncodingId.VORTEX_VARIANT));
        }

        @Test
        void core2026_08_0_addsToPreviousCore() {
            // Given — the latest frozen core edition, adding the canonical Map encoding
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.CORE_2026_08_0);

            // Then — the 31 above plus vortex.map; 32 total
            assertThat(result).hasSize(32).isEqualTo(union(CORE_2025_05_0_BASELINE,
                    EncodingId.VORTEX_PCO, EncodingId.VORTEX_SEQUENCE, EncodingId.VORTEX_ZSTD,
                    EncodingId.FASTLANES_RLE, EncodingId.VORTEX_FIXED_SIZE_LIST,
                    EncodingId.VORTEX_LISTVIEW, EncodingId.VORTEX_MASKED,
                    EncodingId.VORTEX_VARIANT, EncodingId.VORTEX_MAP));
        }

        @Test
        void unstable2025_05_0_isExactlyItsOwnAddition() {
            // Given the first unstable edition — no earlier unstable edition to accumulate from
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.UNSTABLE_2025_05_0);

            // Then
            assertThat(result).containsExactly(EncodingId.FASTLANES_DELTA);
        }

        @Test
        void unstable2026_02_0_addsToThePreviousUnstable() {
            // Given
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.UNSTABLE_2026_02_0);

            // Then
            assertThat(result).containsExactlyInAnyOrder(
                    EncodingId.FASTLANES_DELTA, new EncodingId.Custom("vortex.zstd_buffers"));
        }

        @Test
        void unstable2026_04_0_addsToPreviousUnstable() {
            // Given
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.UNSTABLE_2026_04_0);

            // Then
            assertThat(result).containsExactlyInAnyOrder(
                    EncodingId.FASTLANES_DELTA, new EncodingId.Custom("vortex.zstd_buffers"),
                    new EncodingId.Custom("vortex.parquet.variant"), EncodingId.VORTEX_PATCHED,
                    new EncodingId.Custom("vortex.tensor.cosine_similarity"),
                    new EncodingId.Custom("vortex.tensor.inner_product"),
                    new EncodingId.Custom("vortex.tensor.l2_denorm"),
                    new EncodingId.Custom("vortex.tensor.l2_norm"));
        }

        @Test
        void unstable2026_06_0_isTheFullUnstableSet() {
            // Given — the latest draft unstable edition
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.UNSTABLE_2026_06_0);

            // Then
            assertThat(result).hasSize(9).containsExactlyInAnyOrder(
                    EncodingId.FASTLANES_DELTA, new EncodingId.Custom("vortex.zstd_buffers"),
                    new EncodingId.Custom("vortex.parquet.variant"), EncodingId.VORTEX_PATCHED,
                    new EncodingId.Custom("vortex.tensor.cosine_similarity"),
                    new EncodingId.Custom("vortex.tensor.inner_product"),
                    new EncodingId.Custom("vortex.tensor.l2_denorm"),
                    new EncodingId.Custom("vortex.tensor.l2_norm"),
                    new EncodingId.Custom("vortex.onpair"));
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
                    EncodingId.VORTEX_VARIANT);              // core2026.07.0
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
            // Given — vortex.map joins at the newest core edition, the last entry scanned
            // When
            Optional<Edition> result = Editions.owningEdition(EncodingId.VORTEX_MAP);

            // Then
            assertThat(result).contains(Editions.CORE_2026_08_0);
        }

        @Test
        void owningEdition_unstableFamilyId_returnsTheUnstableEdition() {
            // Given
            // When
            Optional<Edition> result = Editions.owningEdition(EncodingId.VORTEX_PATCHED);

            // Then
            assertThat(result).contains(Editions.UNSTABLE_2026_04_0);
            assertThat(result.get().id().family()).isEqualTo(EditionFamily.UNSTABLE);
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
