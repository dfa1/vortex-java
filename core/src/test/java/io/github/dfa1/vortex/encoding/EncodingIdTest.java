package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncodingIdTest {

    @Nested
    class From {

        @ParameterizedTest
        @EnumSource(EncodingId.class)
        void from_knownId_roundTrips(EncodingId id) {
            // Given / When
            EncodingId result = EncodingId.from(id.id());

            // Then
            assertThat(result).isSameAs(id);
        }

        @Test
        void from_unknownId_throwsVortexException() {
            // Given / When / Then
            assertThatThrownBy(() -> EncodingId.from("vortex.does.not.exist"))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("vortex.does.not.exist");
        }
    }

    @Nested
    class TryFrom {

        @ParameterizedTest
        @EnumSource(EncodingId.class)
        void tryFrom_knownId_returnsMatchingConstant(EncodingId id) {
            // Given / When
            EncodingId result = EncodingId.tryFrom(id.id());

            // Then
            assertThat(result).isSameAs(id);
        }

        @Test
        void tryFrom_unknownId_returnsNull() {
            // Given / When / Then
            assertThat(EncodingId.tryFrom("supermario")).isNull();
        }
    }

    @Nested
    class Properties {

        @ParameterizedTest
        @EnumSource(EncodingId.class)
        void id_isNonBlankString(EncodingId id) {
            // Given / When / Then
            assertThat(id.id()).isNotBlank();
        }

        @ParameterizedTest
        @EnumSource(EncodingId.class)
        void toString_equalsId(EncodingId id) {
            // Given / When / Then
            assertThat(id.toString()).isEqualTo(id.id());
        }
    }
}
