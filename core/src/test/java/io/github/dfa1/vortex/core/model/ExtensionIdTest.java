package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionIdTest {

    @ParameterizedTest
    @CsvSource({
            "vortex.date,VORTEX_DATE",
            "vortex.time,VORTEX_TIME",
            "vortex.timestamp,VORTEX_TIMESTAMP",
            "vortex.uuid,VORTEX_UUID"
    })
    void parse_knownIds_returnEnumConstant(String wire, ExtensionId expected) {
        // Given / When / Then — wire string round-trips to the enum constant
        // so the LOOKUP map stays in sync with the enum definition
        assertThat(ExtensionId.parse(wire)).contains(expected);
    }

    @Test
    void parse_unknownId_returnsEmpty() {
        // Given — open-world extension id; library doesn't recognize it
        // When / Then — non-throwing miss so the registry can route to passthrough
        assertThat(ExtensionId.parse("acme.geopoint")).isEmpty();
    }

    @Test
    void id_matchesEnumStringRepresentation() {
        // Given / When / Then — id() is the wire-format string used in toString()
        // so debug output and switch-case keys stay aligned
        for (ExtensionId id : ExtensionId.values()) {
            assertThat(id.id()).isEqualTo(id.toString());
        }
    }
}
