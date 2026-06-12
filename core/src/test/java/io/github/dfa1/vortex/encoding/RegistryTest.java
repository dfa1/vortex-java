package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryTest {

    @Test
    void extensionLookup_serviceLoadedReturnsImpl() {
        // Given — defaults populate extension namespace from ServiceLoader; the 4 spec
        // extensions must be reachable by typed id without any explicit registration.
        Registry sut = Registry.loadAll();

        // When / Then
        assertThat(sut.lookup(io.github.dfa1.vortex.extension.ExtensionId.VORTEX_DATE))
                .isExactlyInstanceOf(io.github.dfa1.vortex.extension.DateExtension.class);
        assertThat(sut.lookup(io.github.dfa1.vortex.extension.ExtensionId.VORTEX_TIME))
                .isExactlyInstanceOf(io.github.dfa1.vortex.extension.TimeExtension.class);
        assertThat(sut.lookup(io.github.dfa1.vortex.extension.ExtensionId.VORTEX_TIMESTAMP))
                .isExactlyInstanceOf(io.github.dfa1.vortex.extension.TimestampExtension.class);
        assertThat(sut.lookup(io.github.dfa1.vortex.extension.ExtensionId.VORTEX_UUID))
                .isExactlyInstanceOf(io.github.dfa1.vortex.extension.UuidExtension.class);
    }

    @Test
    void extensionLookup_emptyRegistryReturnsNull() {
        // Given
        Registry sut = Registry.empty();

        // When / Then
        assertThat(sut.lookup(io.github.dfa1.vortex.extension.ExtensionId.VORTEX_DATE)).isNull();
    }

    @Test
    void extensionDuplicateRegistration_throws() {
        // Given
        Registry.Builder sut = Registry.builder()
                .register(io.github.dfa1.vortex.extension.DateExtension.INSTANCE);

        // When / Then
        assertThatThrownBy(() -> sut.register(io.github.dfa1.vortex.extension.DateExtension.INSTANCE))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("already registered");
    }
}
