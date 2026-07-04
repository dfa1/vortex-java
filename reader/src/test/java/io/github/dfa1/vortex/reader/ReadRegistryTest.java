package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.UnknownArray;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.EncodingDecoder;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

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
        assertThat(((UnknownArray) result).encodingId()).isEqualTo("vortex.primitive");
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
        assertThat(unknown.encodingId()).isEqualTo("some.unknown");
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
        assertThat(((UnknownArray) unknown.children()[0]).encodingId()).isEqualTo("vortex.primitive");
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

    private static EncodingDecoder decoder(EncodingId id) {
        EncodingDecoder decoder = mock(EncodingDecoder.class);
        given(decoder.encodingId()).willReturn(id);
        return decoder;
    }
}
