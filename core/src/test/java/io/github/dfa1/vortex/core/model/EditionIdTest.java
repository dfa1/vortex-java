package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class EditionIdTest {

    @Nested
    class ToString {

        @Test
        void toString_zeroPadsMonthButNotVersion() {
            // Given — month 5 must render as "05", version 0 must render as "0" (not "00")
            EditionId sut = new EditionId(EditionFamily.CORE, YearMonth.of(2025, 5), 0);

            // When
            String result = sut.toString();

            // Then
            assertThat(result).isEqualTo("core2025.05.0");
        }

        @Test
        void toString_doubleDigitMonthAndNonZeroVersion() {
            // Given
            EditionId sut = new EditionId(EditionFamily.UNSTABLE, YearMonth.of(2026, 11), 2);

            // When
            String result = sut.toString();

            // Then
            assertThat(result).isEqualTo("unstable2026.11.2");
        }
    }

    @Nested
    class IsAtOrBefore {

        @Test
        void isAtOrBefore_earlierYear_isTrue() {
            // Given
            EditionId earlier = new EditionId(EditionFamily.CORE, YearMonth.of(2025, 5), 0);
            EditionId later = new EditionId(EditionFamily.CORE, YearMonth.of(2026, 7), 0);

            // When
            boolean result = earlier.isAtOrBefore(later);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void isAtOrBefore_sameEdition_isTrue() {
            // Given
            EditionId sut = new EditionId(EditionFamily.CORE, YearMonth.of(2025, 5), 0);

            // When
            boolean result = sut.isAtOrBefore(sut);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void isAtOrBefore_laterEdition_isFalse() {
            // Given
            EditionId later = new EditionId(EditionFamily.CORE, YearMonth.of(2026, 7), 0);
            EditionId earlier = new EditionId(EditionFamily.CORE, YearMonth.of(2025, 5), 0);

            // When
            boolean result = later.isAtOrBefore(earlier);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        void isAtOrBefore_sameYearEarlierMonth_isTrue() {
            // Given
            EditionId sut = new EditionId(EditionFamily.CORE, YearMonth.of(2025, 5), 0);
            EditionId other = new EditionId(EditionFamily.CORE, YearMonth.of(2025, 6), 0);

            // When / Then
            assertThat(sut.isAtOrBefore(other)).isTrue();
        }

        @Test
        void isAtOrBefore_sameYearMonthEarlierVersion_isTrue() {
            // Given
            EditionId sut = new EditionId(EditionFamily.CORE, YearMonth.of(2025, 5), 0);
            EditionId other = new EditionId(EditionFamily.CORE, YearMonth.of(2025, 5), 1);

            // When / Then
            assertThat(sut.isAtOrBefore(other)).isTrue();
        }

        @Test
        void isAtOrBefore_differentFamily_isAlwaysFalse() {
            // Given — unstable2025.05.0 is chronologically "earlier" than core2026.07.0, but
            // families are never ordered against each other
            EditionId unstable = new EditionId(EditionFamily.UNSTABLE, YearMonth.of(2025, 5), 0);
            EditionId core = new EditionId(EditionFamily.CORE, YearMonth.of(2026, 7), 0);

            // When / Then
            assertThat(unstable.isAtOrBefore(core)).isFalse();
            assertThat(core.isAtOrBefore(unstable)).isFalse();
        }
    }
}
