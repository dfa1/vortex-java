package io.github.dfa1.vortex.encoding;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class EncodingIdTest {

    @Nested
    class Parse {

        @ParameterizedTest
        @EnumSource(EncodingId.class)
        void parse_knownId_returnsMatchingConstant(EncodingId id) {
            // Given / When / Then — every declared constant round-trips through its wire id
            assertThat(EncodingId.parse(id.id())).contains(id);
        }

        @Test
        void parse_unknownId_returnsEmpty() {
            // Given / When / Then — non-throwing miss so the registry can route to passthrough
            assertThat(EncodingId.parse("supermario")).isEmpty();
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
            assertThat(id).hasToString(id.id());
        }
    }
}
