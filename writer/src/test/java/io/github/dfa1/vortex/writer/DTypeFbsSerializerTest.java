package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.ExtensionMetadata;
import io.github.dfa1.vortex.core.model.OpaqueMetadata;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimeUnitMetadata;
import io.github.dfa1.vortex.core.model.TimestampMetadata;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

/// The writer half of Rust's `serialize_metadata`: what `DTypeFbsSerializer#metadataBytes` writes
/// for each kind of extension metadata, and what it refuses before a file is written.
class DTypeFbsSerializerTest {

    private static final ExtensionId GEOPOINT = ExtensionId.parse("acme.geopoint");

    /// A third-party extension's typed metadata.
    private record Srid(int srid) implements ExtensionMetadata {
    }

    @Test
    void builtInRecords_underTheirOwnIds_areWrittenAsTheReaderParsesThem() {
        // Given — no encoders: the built-ins need none
        DTypeFbsSerializer sut = new DTypeFbsSerializer(WriteRegistry.builder().build());

        // When
        byte[] date = sut.metadataBytes(extension("vortex.date", new TimeUnitMetadata(TimeUnit.Days)));
        byte[] timestamp = sut.metadataBytes(extension("vortex.timestamp",
                new TimestampMetadata(TimeUnit.Milliseconds, "UTC")));

        // Then — the unit tag; then for a timestamp the u16 LE zone length and the zone as stored
        assertThat(date).containsExactly(4);
        assertThat(timestamp).containsExactly(2, 3, 0, 'U', 'T', 'C');
    }

    @Test
    void builtInRecord_underACustomId_goesThroughThatIdsEncoder() {
        // Given — a custom id reusing TimeUnitMetadata: written by the built-in path it would read
        // back as opaque bytes, which its own decoder could not tell from any other blob
        ExtensionEncoder encoder = encoder(GEOPOINT);
        given(encoder.serializeMetadata(any())).willReturn(new byte[]{7});
        DTypeFbsSerializer sut = new DTypeFbsSerializer(WriteRegistry.builder().register(encoder).build());

        // When
        byte[] result = sut.metadataBytes(extension(GEOPOINT.id(), new TimeUnitMetadata(TimeUnit.Seconds)));

        // Then
        assertThat(result).containsExactly(7);
    }

    @Test
    void encoderBytes_areSerializedOncePerExtension() {
        // Given — the writer checks the metadata at create and writes it at close
        ExtensionEncoder encoder = encoder(GEOPOINT);
        given(encoder.serializeMetadata(any())).willReturn(new byte[]{1});
        DTypeFbsSerializer sut = new DTypeFbsSerializer(WriteRegistry.builder().register(encoder).build());
        DType.Extension ext = extension(GEOPOINT.id(), new Srid(4326));

        // When
        sut.metadataBytes(ext);
        sut.metadataBytes(ext);

        // Then — a third-party serializer runs once, so the bytes written are the bytes checked
        then(encoder).should(times(1)).serializeMetadata(any());
    }

    @Test
    void oversizedMetadata_isRefused() {
        // Given — bytes the reader's 64 KiB cap would refuse: the writer must not produce a file
        // its own reader cannot open
        ExtensionEncoder encoder = encoder(GEOPOINT);
        given(encoder.serializeMetadata(any())).willReturn(new byte[(int) OpaqueMetadata.MAX_SIZE.bytes() + 1]);
        DTypeFbsSerializer sut = new DTypeFbsSerializer(WriteRegistry.builder().register(encoder).build());

        // When / Then
        assertThatThrownBy(() -> sut.metadataBytes(extension(GEOPOINT.id(), new Srid(1))))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("acme.geopoint metadata serializes to 65537 bytes");
    }

    @Test
    void encoderReturningNull_isRefusedNamingTheId() {
        // Given
        ExtensionEncoder encoder = encoder(GEOPOINT);
        given(encoder.serializeMetadata(any())).willReturn(null);
        DTypeFbsSerializer sut = new DTypeFbsSerializer(WriteRegistry.builder().register(encoder).build());

        // When / Then — a VortexException naming the extension, not an NPE inside the FlatBuffer builder
        assertThatThrownBy(() -> sut.metadataBytes(extension(GEOPOINT.id(), new Srid(1))))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("acme.geopoint encoder serialized its metadata to null");
    }

    @Test
    void customRecordWithoutEncoder_isRefused() {
        // Given
        DTypeFbsSerializer sut = new DTypeFbsSerializer(WriteRegistry.builder().build());

        // When / Then
        assertThatThrownBy(() -> sut.metadataBytes(extension(GEOPOINT.id(), new Srid(1))))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("no extension encoder registered for acme.geopoint");
    }

    @Test
    void opaqueMetadata_isWrittenAsItsBytes() {
        // Given — a foreign id's bytes, kept from the file it was read from
        DTypeFbsSerializer sut = new DTypeFbsSerializer(WriteRegistry.builder().build());
        byte[] bytes = "crs".getBytes(StandardCharsets.US_ASCII);

        // When
        byte[] result = sut.metadataBytes(extension("vortex.st.wkb", new OpaqueMetadata(MemorySegment.ofArray(bytes))));

        // Then
        assertThat(result).isEqualTo(bytes);
    }

    private static DType.Extension extension(String id, ExtensionMetadata metadata) {
        return new DType.Extension(id, DTypes.I64, metadata, false);
    }

    private static ExtensionEncoder encoder(ExtensionId id) {
        ExtensionEncoder encoder = mock(ExtensionEncoder.class);
        given(encoder.extensionId()).willReturn(id);
        return encoder;
    }
}
