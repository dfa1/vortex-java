package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.UnknownArray;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.ExtensionMetadata;
import io.github.dfa1.vortex.core.model.OpaqueMetadata;
import io.github.dfa1.vortex.reader.extension.DateExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.ExtensionDecoder;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.EncodingDecoder;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ReadRegistryTest {

    @Test
    void decodeUnknownEncodingThrowsByDefault() {
        // Given
        ReadRegistry sut = ReadRegistry.empty();
        ArrayNode node = new ArrayNode(EncodingId.parse("some.unknown"),
                MemorySegment.ofArray(new byte[0]), new ArrayNode[0], new int[0]);
        DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0L,
                new MemorySegment[0], sut, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("some.unknown");
    }

    @Test
    void decodeKnownEncodingWithoutDecoderThrowsByDefault() {
        // Given — EncodingId is known but no decoder registered for it
        ReadRegistry sut = ReadRegistry.empty();
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_PRIMITIVE,
                MemorySegment.ofArray(new byte[0]), new ArrayNode[0], new int[0]);
        DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0L,
                new MemorySegment[0], sut, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("vortex.primitive");
    }

    @Test
    void decodeCoreEditionEncodingWithoutDecoder_namesTheEditionInTheMessage() {
        // Given — vortex.zstd first joins core2025.06.0, no decoder registered for it (issue #301)
        ReadRegistry sut = ReadRegistry.empty();
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZSTD,
                MemorySegment.ofArray(new byte[0]), new ArrayNode[0], new int[0]);
        DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0L,
                new MemorySegment[0], sut, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("joined edition core2025.06.0")
                .hasMessageContaining("allowUnknown")
                .hasMessageNotContaining("unknown to all editions");
    }

    @Test
    void decodeEncodingInNoEditionWithoutDecoder_saysUnknownToAllEditions() {
        // Given — Rust declares fastlanes.delta in no edition
        ReadRegistry sut = ReadRegistry.empty();
        ArrayNode node = new ArrayNode(EncodingId.FASTLANES_DELTA,
                MemorySegment.ofArray(new byte[0]), new ArrayNode[0], new int[0]);
        DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0L,
                new MemorySegment[0], sut, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("unknown to all editions");
    }

    @Test
    void decodeUnknownEncoding_saysUnknownToAllEditions() {
        // Given — not a WellKnown id, and no edition declares it either
        ReadRegistry sut = ReadRegistry.empty();
        ArrayNode node = new ArrayNode(EncodingId.parse("some.unknown"),
                MemorySegment.ofArray(new byte[0]), new ArrayNode[0], new int[0]);
        DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0L,
                new MemorySegment[0], sut, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("unknown to all editions");
    }

    @Test
    void decodeAsSegmentKnownEditionEncodingWithoutDecoder_alsoNamesTheEdition() {
        // Given — decodeAsSegment shares the same enriched message as decode
        ReadRegistry sut = ReadRegistry.empty();
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZSTD,
                MemorySegment.ofArray(new byte[0]), new ArrayNode[0], new int[0]);
        DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0L,
                new MemorySegment[0], sut, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> sut.decodeAsSegment(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("joined edition core2025.06.0")
                .hasMessageContaining("no primary segment");
    }

    @Test
    void decodeKnownEncodingWithoutDecoderReturnsUnknownArrayWhenAllowed() {
        // Given
        ReadRegistry sut = ReadRegistry.builder().allowUnknown().build();
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_PRIMITIVE,
                MemorySegment.ofArray(new byte[0]), new ArrayNode[0], new int[0]);
        DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0L,
                new MemorySegment[0], sut, Arena.ofAuto());

        // When
        Array result = sut.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(UnknownArray.class);
        assertThat(((UnknownArray) result).encodingId()).isEqualTo(EncodingId.VORTEX_PRIMITIVE);
    }

    @Test
    void decodeUnknownEncodingReturnsUnknownArrayWhenAllowed() {
        // Given
        ReadRegistry sut = ReadRegistry.builder().allowUnknown().build();
        MemorySegment metadata = MemorySegment.ofArray(new byte[]{1, 2, 3});
        MemorySegment buf = Arena.ofAuto().allocate(4);
        buf.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 42);
        ArrayNode node = new ArrayNode(EncodingId.parse("some.unknown"),
                metadata, new ArrayNode[0], new int[]{0});
        DecodeContext ctx = new DecodeContext(node, DTypes.I32, 5L,
                new MemorySegment[]{buf}, sut, Arena.ofAuto());

        // When
        Array result = sut.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(UnknownArray.class);
        UnknownArray unknown = (UnknownArray) result;
        assertThat(unknown.encodingId()).isEqualTo(new EncodingId.Custom("some.unknown"));
        assertThat(unknown.dtype()).isEqualTo(DTypes.I32);
        assertThat(unknown.length()).isEqualTo(5L);
        assertThat(unknown.metadata()).isEqualTo(metadata);
        assertThat(unknown.buffers()).hasSize(1);
        assertThat(unknown.buffers()[0].get(java.lang.foreign.ValueLayout.JAVA_INT, 0)).isEqualTo(42);
        assertThat(unknown.children()).isEmpty();
    }

    @Test
    void decodeUnknownEncodingWrapsChildrenAsUnknown() {
        // Given
        ReadRegistry sut = ReadRegistry.builder().allowUnknown().build();
        // Child uses a known id; allow-unknown still wraps it unknown because
        // its parent is unknown — mirrors Rust decode_foreign in vortex-array/src/serde.rs:380.
        ArrayNode child = new ArrayNode(EncodingId.VORTEX_PRIMITIVE,
                MemorySegment.ofArray(new byte[0]), new ArrayNode[0], new int[0]);
        ArrayNode parent = new ArrayNode(EncodingId.parse("some.unknown"),
                MemorySegment.ofArray(new byte[0]), new ArrayNode[]{child}, new int[0]);
        DecodeContext ctx = new DecodeContext(parent, DTypes.I32, 0L,
                new MemorySegment[0], sut, Arena.ofAuto());

        // When
        Array result = sut.decode(ctx);

        // Then
        UnknownArray unknown = (UnknownArray) result;
        assertThat(unknown.children()).hasSize(1);
        assertThat(unknown.children()[0]).isInstanceOf(UnknownArray.class);
        assertThat(((UnknownArray) unknown.children()[0]).encodingId()).isEqualTo(EncodingId.VORTEX_PRIMITIVE);
        assertThat(sut.isAllowUnknown()).isTrue();
    }

    @Test
    void builder_retainsAllRegisteredDecoders() {
        // Given — decoders registered out of natural id order (the registry sorts into a TreeMap)
        EncodingDecoder bool = decoder(EncodingId.VORTEX_BOOL);
        EncodingDecoder primitive = decoder(EncodingId.VORTEX_PRIMITIVE);

        // When
        ReadRegistry result = ReadRegistry.builder().register(bool).register(primitive).build();

        // Then — every registered decoder is retained, regardless of registration order
        assertThat(result.hasDecoder(EncodingId.VORTEX_PRIMITIVE)).isTrue();
        assertThat(result.hasDecoder(EncodingId.VORTEX_BOOL)).isTrue();
        assertThat(result.hasDecoder(EncodingId.VORTEX_CONSTANT)).isFalse();
    }

    @Test
    void registerExtension_lookupFindsCustomAndBuiltIn() {
        // Given — a Custom id next to the built-ins registerDefaults() adds
        ExtensionDecoder<?> custom = extension("acme.geopoint");

        // When
        ReadRegistry result = ReadRegistry.builder().registerDefaults().register(custom).build();

        // Then
        assertThat(result.lookup(ExtensionId.parse("acme.geopoint"))).isSameAs(custom);
        assertThat(result.lookup(ExtensionId.VORTEX_DATE)).isSameAs(DateExtensionDecoder.INSTANCE);
    }

    @Test
    void registerExtension_duplicateId_throws() {
        // Given
        ReadRegistry.Builder sut = ReadRegistry.builder().register(extension("acme.geopoint"));
        ExtensionDecoder<?> duplicate = extension("acme.geopoint");

        // When / Then
        assertThatThrownBy(() -> sut.register(duplicate))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void resolveExtensions_unregisteredNestedId_throwsUnlessAllowUnknown() {
        // Given — the unknown id sits inside a list inside a struct: Rust checks every extension
        // dtype it deserializes, not only top-level columns
        DType schema = new DType.Struct(List.of(ColumnName.of("c")),
                List.of(new DType.List(geopoint(OpaqueMetadata.EMPTY), false)), false);
        ReadRegistry strict = ReadRegistry.loadAll();
        ReadRegistry lenient = ReadRegistry.builder().registerDefaults().allowUnknown().build();

        // When
        DType result = lenient.resolveExtensions(schema);

        // Then — allowed, an unknown id keeps its bytes (Rust's ForeignExtDType)
        assertThat(result).isEqualTo(schema);
        assertThatThrownBy(() -> strict.resolveExtensions(schema))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("no extension decoder registered for acme.geopoint");
    }

    @Test
    void resolveExtensions_registeredDecoder_parsesItsMetadata() {
        // Given — a decoder that parses its bytes into a record, nested in a list in a struct so
        // the resolved dtype has to be rebuilt around it
        ExtensionDecoder<?> decoder = extension("acme.geopoint");
        // will…given form: given(decoder.parseMetadata(any())) would run the previous stub with null
        org.mockito.BDDMockito.willAnswer(inv -> new Srid(((MemorySegment) inv.getArgument(0)).get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0)))
                .given(decoder).parseMetadata(org.mockito.ArgumentMatchers.any());
        ReadRegistry sut = ReadRegistry.builder().register(decoder).build();
        DType schema = new DType.Struct(List.of(ColumnName.of("c")), List.of(new DType.List(
                geopoint(new OpaqueMetadata(MemorySegment.ofArray(new byte[]{42}))), false)), false);

        // When
        DType result = sut.resolveExtensions(schema);

        // Then — the column now carries the decoder's typed metadata
        assertThat(result).isEqualTo(new DType.Struct(List.of(ColumnName.of("c")),
                List.of(new DType.List(geopoint(new Srid(42)), false)), false));
    }

    @Test
    void resolveExtensions_nothingToParse_returnsTheSameInstance() {
        // Given — a schema whose extension's decoder keeps the bytes, as the default does. The
        // Variant and Constant decoders resolve per chunk, so an unchanged tree must not be rebuilt.
        ReadRegistry sut = ReadRegistry.builder().register(extension("acme.geopoint")).build();
        DType schema = new DType.Struct(List.of(ColumnName.of("c"), ColumnName.of("d")),
                List.of(DTypes.I64, new DType.List(geopoint(OpaqueMetadata.EMPTY), false)), false);

        // When
        DType result = sut.resolveExtensions(schema);

        // Then
        assertThat(result).isSameAs(schema);
    }

    @Test
    void resolveExtensions_parserReturnsNonRecord_wrapsInVortexException() {
        // Given — a third-party parser returning metadata without value equality
        ExtensionDecoder<?> decoder = extension("acme.geopoint");
        org.mockito.BDDMockito.willReturn(new ExtensionMetadata() {
        }).given(decoder).parseMetadata(org.mockito.ArgumentMatchers.any());
        ReadRegistry sut = ReadRegistry.builder().register(decoder).build();

        // When / Then — reading a file only ever fails with VortexException
        assertThatThrownBy(() -> sut.resolveExtensions(geopoint(OpaqueMetadata.EMPTY)))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("acme.geopoint rejected its metadata")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveExtensions_registeredDecoder_runsItsValidate() {
        // Given — a decoder whose validate refuses every dtype
        ExtensionDecoder<?> refusing = extension("acme.geopoint");
        org.mockito.BDDMockito.willThrow(new VortexException("bad geopoint")).given(refusing).validate(org.mockito.ArgumentMatchers.any());
        ReadRegistry sut = ReadRegistry.builder().register(refusing).build();
        DType schema = new DType.Struct(List.of(ColumnName.of("c")), List.of(geopoint(OpaqueMetadata.EMPTY)), false);

        // When / Then
        assertThatThrownBy(() -> sut.resolveExtensions(schema))
                .isInstanceOf(VortexException.class)
                .hasMessage("bad geopoint");
    }

    @Test
    void resolveExtensions_noDtype_passes() {
        // Given — a file without a dtype blob parses with a null schema (PostscriptParserBlobBoundsTest);
        // the walk's switch used to throw NullPointerException on it
        ReadRegistry sut = ReadRegistry.loadAll();

        // When
        DType result = sut.resolveExtensions(null);

        // Then
        assertThat(result).isNull();
    }

    @Test
    void resolveExtensions_validateThrowsRuntimeException_wrapsInVortexException() {
        // Given — a third-party validate that trips over malformed metadata from the file
        ExtensionDecoder<?> fragile = extension("acme.geopoint");
        IndexOutOfBoundsException cause = new IndexOutOfBoundsException("metadata too short");
        org.mockito.BDDMockito.willThrow(cause).given(fragile).validate(org.mockito.ArgumentMatchers.any());
        ReadRegistry sut = ReadRegistry.builder().register(fragile).build();

        // When / Then — reading a file only ever fails with VortexException
        assertThatThrownBy(() -> sut.resolveExtensions(geopoint(OpaqueMetadata.EMPTY)))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("acme.geopoint")
                .hasCause(cause);
    }

    @Test
    void resolveExtensions_wellKnownIdWithoutDecoder_passesStrictMode() {
        // Given — vortex.json is in the vortex-jni default session, so a strict reader must open it
        // (reading the column as its Utf8 storage) though Java registers no decoder for it
        DType schema = new DType.Extension("vortex.json", DTypes.UTF8, OpaqueMetadata.EMPTY, false);
        ReadRegistry sut = ReadRegistry.loadAll();

        // When
        DType result = sut.resolveExtensions(schema);

        // Then
        assertThat(result).isEqualTo(schema);
    }

    @Test
    void resolveExtensions_wellKnownIdWithoutDecoder_stillValidated() {
        // Given — no decoder, but Rust's rules for the id still apply
        DType schema = new DType.Extension("vortex.json", DTypes.I32, OpaqueMetadata.EMPTY, false);
        ReadRegistry sut = ReadRegistry.loadAll();

        // When / Then
        assertThatThrownBy(() -> sut.resolveExtensions(schema))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("storage dtype must be Utf8");
    }

    /// A third-party extension's typed metadata.
    private record Srid(int srid) implements ExtensionMetadata {
    }

    private static DType.Extension geopoint(ExtensionMetadata metadata) {
        return new DType.Extension("acme.geopoint", DTypes.I32, metadata, false);
    }

    private static EncodingDecoder decoder(EncodingId id) {
        EncodingDecoder decoder = mock(EncodingDecoder.class);
        given(decoder.encodingId()).willReturn(id);
        return decoder;
    }

    /// A mocked decoder; its `parseMetadata` keeps the bytes, as the interface's default does
    /// (a mock would return `null`).
    private static ExtensionDecoder<?> extension(String id) {
        ExtensionDecoder<?> decoder = mock(ExtensionDecoder.class);
        given(decoder.extensionId()).willReturn(ExtensionId.parse(id));
        given(decoder.parseMetadata(org.mockito.ArgumentMatchers.any()))
                .willAnswer(inv -> new OpaqueMetadata(inv.getArgument(0)));
        return decoder;
    }
}
