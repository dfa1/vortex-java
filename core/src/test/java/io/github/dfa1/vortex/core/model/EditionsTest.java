package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Golden tests pinning the ground-truth edition data from
/// [vortex-data/vortex#8871](https://github.com/vortex-data/vortex/pull/8871): a stray edit to
/// [Editions]'s declarations that changes a cumulative member set fails here, mirroring upstream's
/// own `validate_edition` test harness.
class EditionsTest {

    @Nested
    class CumulativeMembers {

        @Test
        void core2025_05_0_isExactlyItsOwnBaseline() {
            // Given the first core edition — no earlier core edition to accumulate from
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.CORE_2025_05_0);

            // Then
            assertThat(result).isEqualTo(ids(
                    "fastlanes.bitpacked", "fastlanes.for",
                    "vortex.alp", "vortex.alprd", "vortex.bool", "vortex.bytebool", "vortex.chunked",
                    "vortex.constant", "vortex.datetimeparts", "vortex.decimal", "vortex.decimal_byte_parts",
                    "vortex.dict", "vortex.ext", "vortex.fsst", "vortex.list", "vortex.null",
                    "vortex.primitive", "vortex.runend", "vortex.sparse", "vortex.struct", "vortex.varbin",
                    "vortex.varbinview", "vortex.zigzag"));
        }

        @Test
        void core2025_06_0_addsToTheBaseline() {
            // Given
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.CORE_2025_06_0);

            // Then — baseline 23 plus pco/sequence/zstd
            assertThat(result).isEqualTo(ids(
                    "fastlanes.bitpacked", "fastlanes.for",
                    "vortex.alp", "vortex.alprd", "vortex.bool", "vortex.bytebool", "vortex.chunked",
                    "vortex.constant", "vortex.datetimeparts", "vortex.decimal", "vortex.decimal_byte_parts",
                    "vortex.dict", "vortex.ext", "vortex.fsst", "vortex.list", "vortex.null",
                    "vortex.primitive", "vortex.runend", "vortex.sparse", "vortex.struct", "vortex.varbin",
                    "vortex.varbinview", "vortex.zigzag",
                    "vortex.pco", "vortex.sequence", "vortex.zstd"));
        }

        @Test
        void core2025_10_0_addsToPreviousCore() {
            // Given
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.CORE_2025_10_0);

            // Then — the 26 above plus rle/fixed_size_list/listview/masked
            assertThat(result).isEqualTo(ids(
                    "fastlanes.bitpacked", "fastlanes.for", "fastlanes.rle",
                    "vortex.alp", "vortex.alprd", "vortex.bool", "vortex.bytebool", "vortex.chunked",
                    "vortex.constant", "vortex.datetimeparts", "vortex.decimal", "vortex.decimal_byte_parts",
                    "vortex.dict", "vortex.ext", "vortex.fixed_size_list", "vortex.fsst", "vortex.list",
                    "vortex.listview", "vortex.masked", "vortex.null",
                    "vortex.primitive", "vortex.runend", "vortex.sparse", "vortex.struct", "vortex.varbin",
                    "vortex.varbinview", "vortex.zigzag",
                    "vortex.pco", "vortex.sequence", "vortex.zstd"));
        }

        @Test
        void core2026_07_0_isTheFullCoreSet() {
            // Given — the latest frozen core edition
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.CORE_2026_07_0);

            // Then — every core encoding through vortex.variant; 31 total
            assertThat(result).hasSize(31)
                    .isEqualTo(ids(
                            "fastlanes.bitpacked", "fastlanes.for", "fastlanes.rle",
                            "vortex.alp", "vortex.alprd", "vortex.bool", "vortex.bytebool", "vortex.chunked",
                            "vortex.constant", "vortex.datetimeparts", "vortex.decimal", "vortex.decimal_byte_parts",
                            "vortex.dict", "vortex.ext", "vortex.fixed_size_list", "vortex.fsst", "vortex.list",
                            "vortex.listview", "vortex.masked", "vortex.null",
                            "vortex.primitive", "vortex.runend", "vortex.sparse", "vortex.struct", "vortex.varbin",
                            "vortex.varbinview", "vortex.zigzag",
                            "vortex.pco", "vortex.sequence", "vortex.zstd",
                            "vortex.variant"));
        }

        @Test
        void unstable2025_05_0_isExactlyItsOwnAddition() {
            // Given the first unstable edition — no earlier unstable edition to accumulate from
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.UNSTABLE_2025_05_0);

            // Then
            assertThat(result).isEqualTo(ids("fastlanes.delta"));
        }

        @Test
        void unstable2026_02_0_addsToThePreviousUnstable() {
            // Given
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.UNSTABLE_2026_02_0);

            // Then
            assertThat(result).isEqualTo(ids("fastlanes.delta", "vortex.zstd_buffers"));
        }

        @Test
        void unstable2026_04_0_addsToPreviousUnstable() {
            // Given
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.UNSTABLE_2026_04_0);

            // Then
            assertThat(result).isEqualTo(ids(
                    "fastlanes.delta", "vortex.zstd_buffers",
                    "vortex.parquet.variant", "vortex.patched",
                    "vortex.tensor.cosine_similarity", "vortex.tensor.inner_product",
                    "vortex.tensor.l2_denorm", "vortex.tensor.l2_norm"));
        }

        @Test
        void unstable2026_06_0_isTheFullUnstableSet() {
            // Given — the latest draft unstable edition
            // When
            Set<EncodingId> result = Editions.cumulativeMembers(Editions.UNSTABLE_2026_06_0);

            // Then
            assertThat(result).hasSize(9)
                    .isEqualTo(ids(
                            "fastlanes.delta", "vortex.zstd_buffers",
                            "vortex.parquet.variant", "vortex.patched",
                            "vortex.tensor.cosine_similarity", "vortex.tensor.inner_product",
                            "vortex.tensor.l2_denorm", "vortex.tensor.l2_norm",
                            "vortex.onpair"));
        }

        @Test
        void editionNotInAll_cumulativeSeedsFromItsOwnAddedRatherThanRequiringIdentityInAll() {
            // Given — a hypothetical future core edition not (yet) declared in Editions.ALL.
            // Edition's constructor is package-private (only Editions' 8 catalog constants exist
            // in production), so this exercises cumulativeMembers' seeding logic directly rather
            // than a reachable real-world scenario.
            Edition hypothetical = new Edition(
                    new EditionId(EditionFamily.CORE, 2099, 1, 0), Optional.of("9.0.0"),
                    Set.of(EncodingId.parse("vortex.future")));

            // When
            Set<EncodingId> result = Editions.cumulativeMembers(hypothetical);

            // Then — its own addition, plus one member from every earlier core edition
            assertThat(result).contains(
                    EncodingId.parse("vortex.future"),      // its own addition
                    EncodingId.VORTEX_PRIMITIVE,             // core2025.05.0
                    EncodingId.VORTEX_ZSTD,                  // core2025.06.0
                    EncodingId.VORTEX_MASKED,                // core2025.10.0
                    EncodingId.VORTEX_VARIANT);               // core2026.07.0
        }

        private Set<EncodingId> ids(String... rawIds) {
            return Stream.of(rawIds).map(EncodingId::parse).collect(Collectors.toUnmodifiableSet());
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
        void owningEdition_unstableFamilyId_returnsTheDraftEdition() {
            // Given
            // When
            Optional<Edition> result = Editions.owningEdition(EncodingId.VORTEX_PATCHED);

            // Then
            assertThat(result).contains(Editions.UNSTABLE_2026_04_0);
            assertThat(result.get().isDraft()).isTrue();
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
