package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.UnknownArray;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.UnknownArrayNode;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadRegistryTest {

    @Test
    void decodeUnknownEncodingThrowsByDefault() {
        // Given
        ReadRegistry sut = ReadRegistry.empty();
        ArrayNode node = new UnknownArrayNode("some.unknown",
                ByteBuffer.allocate(0), new ArrayNode[0], new int[0], ArrayStats.empty());
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
        ArrayNode node = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE,
                ByteBuffer.allocate(0), new ArrayNode[0], new int[0], ArrayStats.empty());
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
        ArrayNode node = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE,
                ByteBuffer.allocate(0), new ArrayNode[0], new int[0], ArrayStats.empty());
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
        ByteBuffer metadata = ByteBuffer.wrap(new byte[]{1, 2, 3});
        MemorySegment buf = Arena.ofAuto().allocate(4);
        buf.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 42);
        ArrayNode node = new UnknownArrayNode("some.unknown",
                metadata, new ArrayNode[0], new int[]{0}, ArrayStats.empty());
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
        ArrayNode child = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE,
                ByteBuffer.allocate(0), new ArrayNode[0], new int[0], ArrayStats.empty());
        ArrayNode parent = new UnknownArrayNode("some.unknown",
                ByteBuffer.allocate(0), new ArrayNode[]{child}, new int[0], ArrayStats.empty());
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
}
