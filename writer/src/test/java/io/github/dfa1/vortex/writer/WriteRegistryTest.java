package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.writer.encode.EncodingEncoder;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        EncodingEncoder duplicate = encoder(EncodingId.VORTEX_PRIMITIVE);

        // When / Then
        assertThatThrownBy(() -> sut.register(duplicate))
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
        ExtensionEncoder duplicate = extension(ExtensionId.VORTEX_DATE);

        // When / Then
        assertThatThrownBy(() -> sut.register(duplicate))
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
    void loadAll_discoversAllBuiltinEncoders() {
        // Given / When — the module ships all built-in encoders via registerDefaults()
        WriteRegistry sut = WriteRegistry.loadAll();

        // Then
        assertThat(sut.encoderMap()).isNotEmpty();
    }

    @Test
    void build_ordersEncodersByEncodingNameRegardlessOfRegistrationSequence() {
        // Given — three encoders registered out of name order ("vortex.bool" < "vortex.constant"
        // < "vortex.primitive")
        EncodingEncoder bool = encoder(EncodingId.VORTEX_BOOL);
        EncodingEncoder primitive = encoder(EncodingId.VORTEX_PRIMITIVE);
        EncodingEncoder constant = encoder(EncodingId.VORTEX_CONSTANT);

        // When
        WriteRegistry result = WriteRegistry.builder().register(primitive).register(bool).register(constant).build();

        // Then — encoderMap iterates by encoding name no matter the registration sequence, so
        // VortexWriter's first-match encoder selection is deterministic
        assertThat(result.encoderMap().keySet())
                .containsExactly(EncodingId.VORTEX_BOOL, EncodingId.VORTEX_CONSTANT, EncodingId.VORTEX_PRIMITIVE);
    }

    @Test
    void loadAll_encoderOrderIsDeterministicallySortedByName() {
        // Given — all built-in encoders
        WriteRegistry registry = WriteRegistry.loadAll();

        // When — the encoder names in iteration order
        List<String> result = registry.encoderMap().keySet().stream().map(EncodingId::id).toList();

        // Then — sorted by name regardless of registration order
        assertThat(result).isSorted();
    }
}
