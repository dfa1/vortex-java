package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionIdTest {

    @ParameterizedTest
    @CsvSource({
            "vortex.date,VORTEX_DATE",
            "vortex.time,VORTEX_TIME",
            "vortex.timestamp,VORTEX_TIMESTAMP",
            "vortex.uuid,VORTEX_UUID"
    })
    void tryFrom_knownIds_returnEnumConstant(String wire, ExtensionId expected) {
        // Given / When / Then — wire string round-trips to the enum constant
        // so the LOOKUP map stays in sync with the enum definition
        assertThat(ExtensionId.tryFrom(wire)).contains(expected);
    }

    @Test
    void tryFrom_unknownId_returnsEmpty() {
        // Given — open-world extension id; library doesn't recognise it
        // When / Then — non-throwing miss so the registry can route to passthrough
        assertThat(ExtensionId.tryFrom("acme.geopoint")).isEmpty();
    }

    @Test
    void from_unknownId_throws() {
        // Given / When / Then — throwing variant for call sites that demand a known id
        assertThatThrownBy(() -> ExtensionId.from("acme.geopoint"))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("unknown extension id");
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
