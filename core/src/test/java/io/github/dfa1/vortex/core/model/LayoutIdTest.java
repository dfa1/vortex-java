package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LayoutIdTest {

    @Nested
    class Parse {

        @ParameterizedTest
        @EnumSource(LayoutId.WellKnown.class)
        void parse_knownId_returnsMatchingConstant(LayoutId.WellKnown id) {
            // Given the wire string of a well-known constant
            // When
            LayoutId result = LayoutId.parse(id.id());
            // Then the same constant comes back
            assertThat(result).isSameAs(id);
        }

        @Test
        void parse_unknownId_returnsCustomWrappingRawId() {
            // Given a wire string no build knows about
            String raw = "supermario";
            // When — parse is total, so a miss is a typed Custom rather than an empty Optional
            LayoutId result = LayoutId.parse(raw);
            // Then
            assertThat(result).isEqualTo(new LayoutId.Custom(raw));
            assertThat(result.id()).isEqualTo(raw);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   "})
        void parse_blankId_throwsIllegalArgumentException(String blank) {
            // Given / When / Then — blank is not a valid id; parse must not silently wrap it,
            // so untrusted-input callers are forced to guard it into their own domain error
            assertThatThrownBy(() -> LayoutId.parse(blank))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class CustomInvariants {

        @Test
        void construct_nullId_throwsNullPointerException() {
            // Given / When / Then — a Custom must always carry a wire string
            assertThatThrownBy(() -> new LayoutId.Custom(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void construct_blankId_throwsIllegalArgumentException() {
            // Given / When / Then — blank ids have no wire representation
            assertThatThrownBy(() -> new LayoutId.Custom("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void construct_wellKnownId_throwsIllegalArgumentException() {
            // Given a wire string that already names a well-known constant
            // When / Then — Custom refuses to shadow it and points at the constant to use instead
            assertThatThrownBy(() -> new LayoutId.Custom("vortex.flat"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("FLAT");
        }
    }

    @Nested
    class Properties {

        @ParameterizedTest
        @EnumSource(LayoutId.WellKnown.class)
        void id_isNonBlankString(LayoutId.WellKnown id) {
            // Given / When / Then
            assertThat(id.id()).isNotBlank();
        }

        @ParameterizedTest
        @EnumSource(LayoutId.WellKnown.class)
        void toString_equalsId(LayoutId.WellKnown id) {
            // Given / When / Then
            assertThat(id).hasToString(id.id());
        }
    }
}
