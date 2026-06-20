package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.writer.encode.EncodingEncoder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/// Unit tests for [WriteRegistry] and its [WriteRegistry.Builder] — encoder/extension
/// registration, duplicate rejection, lookup, and the fluent builder return.
class WriteRegistryTest {

    private static EncodingEncoder encoder(EncodingId id) {
        EncodingEncoder enc = mock(EncodingEncoder.class);
        given(enc.encodingId()).willReturn(id);
        return enc;
    }

    private static ExtensionEncoder extension(ExtensionId id) {
        ExtensionEncoder ext = mock(ExtensionEncoder.class);
        given(ext.extensionId()).willReturn(id);
        return ext;
    }

    @Test
    void register_thenEncoderMap_containsEncoder() {
        // Given
        EncodingEncoder enc = encoder(EncodingId.VORTEX_PRIMITIVE);

        // When
        WriteRegistry result = WriteRegistry.builder().register(enc).build();

        // Then
        assertThat(result.encoderMap()).containsEntry(EncodingId.VORTEX_PRIMITIVE, enc);
    }

    @Test
    void register_returnsSameBuilderForChaining() {
        // Given
        WriteRegistry.Builder sut = WriteRegistry.builder();

        // When
        WriteRegistry.Builder result = sut.register(encoder(EncodingId.VORTEX_PRIMITIVE));

        // Then — the fluent return must be the same builder, not null
        assertThat(result).isSameAs(sut);
    }

    @Test
    void register_duplicateEncoderId_throws() {
        // Given — two encoders advertising the same id
        WriteRegistry.Builder sut = WriteRegistry.builder()
                .register(encoder(EncodingId.VORTEX_PRIMITIVE));

        // When / Then
        assertThatThrownBy(() -> sut.register(encoder(EncodingId.VORTEX_PRIMITIVE)))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void registerExtension_thenLookup_returnsIt() {
        // Given
        ExtensionEncoder ext = extension(ExtensionId.VORTEX_DATE);

        // When
        WriteRegistry result = WriteRegistry.builder().register(ext).build();

        // Then
        assertThat(result.lookup(ExtensionId.VORTEX_DATE)).isSameAs(ext);
    }

    @Test
    void registerExtension_returnsSameBuilderForChaining() {
        // Given
        WriteRegistry.Builder sut = WriteRegistry.builder();

        // When
        WriteRegistry.Builder result = sut.register(extension(ExtensionId.VORTEX_DATE));

        // Then
        assertThat(result).isSameAs(sut);
    }

    @Test
    void registerExtension_duplicateId_throws() {
        // Given
        WriteRegistry.Builder sut = WriteRegistry.builder()
                .register(extension(ExtensionId.VORTEX_DATE));

        // When / Then
        assertThatThrownBy(() -> sut.register(extension(ExtensionId.VORTEX_DATE)))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void lookup_unregisteredExtension_returnsNull() {
        // Given — empty registry

        // When / Then
        assertThat(WriteRegistry.empty().lookup(ExtensionId.VORTEX_UUID)).isNull();
    }

    @Test
    void empty_hasNoEncodersAndNullLookup() {
        // Given / When
        WriteRegistry sut = WriteRegistry.empty();

        // Then
        assertThat(sut.encoderMap()).isEmpty();
        assertThat(sut.lookup(ExtensionId.VORTEX_DATE)).isNull();
    }

    @Test
    void loadAll_discoversServiceLoadedEncoders() {
        // Given / When — the module ships encoders via META-INF/services
        WriteRegistry sut = WriteRegistry.loadAll();

        // Then
        assertThat(sut.encoderMap()).isNotEmpty();
    }
}
