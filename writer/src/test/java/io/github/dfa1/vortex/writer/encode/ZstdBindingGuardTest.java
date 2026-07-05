package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Covers the optional-zstd-binding guard on the encode side. The binding is always on the test
/// classpath, so the "absent" branches are unreachable through a real encode; the guard logic is
/// factored into class-name- and boolean-parameterized helpers precisely so both branches are
/// exercised here without the jar having to be missing.
class ZstdBindingGuardTest {

    @Test
    void bindingPresent_resolvableClass_returnsTrue() {
        // Given the real binding class name
        // When
        boolean result = ZstdEncodingEncoder.bindingPresent("io.github.dfa1.zstd.ZstdCompressContext");

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void bindingPresent_absentClass_returnsFalse() {
        // Given a class name no classpath provides — exercises the ClassNotFoundException branch
        // When
        boolean result = ZstdEncodingEncoder.bindingPresent("io.github.dfa1.zstd.NoSuchBindingClass");

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void requireBinding_absent_throwsActionableVortexException() {
        // Given / When / Then — the message names the two artifacts a consumer must add
        assertThatThrownBy(() -> ZstdEncodingEncoder.requireBinding(false))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("io.github.dfa1.zstd:zstd")
                .hasMessageContaining("zstd-platform");
    }

    @Test
    void requireBinding_present_doesNotThrow() {
        // Given / When / Then
        assertThatCode(() -> ZstdEncodingEncoder.requireBinding(true)).doesNotThrowAnyException();
    }
}
