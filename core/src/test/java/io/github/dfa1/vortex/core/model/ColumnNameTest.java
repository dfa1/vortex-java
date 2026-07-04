package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The [ColumnName] policy: names a holder certifies were validated once at construction.
/// Printable weirdness is legal (measured against the reference implementation); blank and
/// control-character names are the footguns the policy exists to refuse.
class ColumnNameTest {

    @ParameterizedTest
    @ValueSource(strings = {"price", "$$$$$", "with space inside", "😀emoji", "UPPER_lower.dots-1"})
    void of_printableName_constructsAndRoundTrips(String raw) {
        // Given a printable name of arbitrary shape
        // When
        ColumnName result = ColumnName.of(raw);

        // Then — value, toString, and equality all carry the exact string
        assertThat(result.value()).isEqualTo(raw);
        assertThat(result).hasToString(raw);
        assertThat(result).isEqualTo(new ColumnName(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "a\nb", "tab\there", "nul\u0000here"})
    void of_footgunName_throwsIllegalArgumentException(String raw) {
        // Given / When / Then — blank or control-character names violate the policy
        assertThatThrownBy(() -> ColumnName.of(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_nullName_throwsNullPointerException() {
        // Given / When / Then
        assertThatThrownBy(() -> ColumnName.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void violation_validName_returnsEmpty() {
        // Given a valid name
        // When
        Optional<String> result = ColumnName.violation("price");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void violation_controlCharacter_namesTheCodePoint() {
        // Given a name with an embedded newline
        // When
        Optional<String> result = ColumnName.violation("a\nb");

        // Then — the reason pins the exact code point so boundary errors stay actionable
        assertThat(result).hasValueSatisfying(reason ->
                assertThat(reason).contains("U+000A"));
    }

    @Test
    void compareTo_ordersByValue() {
        // Given two names
        // When
        int result = ColumnName.of("a").compareTo(ColumnName.of("b"));

        // Then
        assertThat(result).isNegative();
    }
}
