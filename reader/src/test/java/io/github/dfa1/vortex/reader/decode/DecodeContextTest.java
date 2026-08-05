package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecodeContextTest {

    @Test
    void buffer_indexPastSegmentCount_throwsVortexException() {
        // Given a node claiming buffer index 1 while only 1 segment (index 0) exists
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        DecodeContext sut = TestDecodeContexts.of(node, DType.UTF8)
                .segments(MemorySegment.ofArray(new byte[4]))
                .arena(Arena.ofAuto())
                .build();

        // When / Then
        assertThatThrownBy(() -> sut.buffer(0))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("out of bounds");
    }

    @Test
    void buffer_negativeIndex_throwsVortexException() {
        // Given a node with a negative buffer index (e.g. corrupted wire data)
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{-1});
        DecodeContext sut = TestDecodeContexts.of(node, DType.UTF8)
                .segments(MemorySegment.ofArray(new byte[4]))
                .arena(Arena.ofAuto())
                .build();

        // When / Then
        assertThatThrownBy(() -> sut.buffer(0))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("out of bounds");
    }

    @Test
    void buffer_positionPastDeclaredBuffers_throwsVortexException() {
        // Given a node that declares no buffers at all, as a truncated legacy dict layout
        // would: the position itself is out of bounds before its value can be checked
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_DICT, null, new ArrayNode[0], new int[0]);
        DecodeContext sut = TestDecodeContexts.of(node, DType.UTF8)
                .segments(MemorySegment.ofArray(new byte[4]))
                .arena(Arena.ofAuto())
                .build();

        // When / Then
        assertThatThrownBy(() -> sut.buffer(0))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("buffer position 0 out of bounds for 0 declared buffer(s)");
    }

    @Test
    void buffer_negativePosition_throwsVortexException() {
        // Given a valid node but a negative position
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        DecodeContext sut = TestDecodeContexts.of(node, DType.UTF8)
                .segments(MemorySegment.ofArray(new byte[4]))
                .arena(Arena.ofAuto())
                .build();

        // When / Then
        assertThatThrownBy(() -> sut.buffer(-1))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("buffer position -1 out of bounds");
    }

    @Test
    void decodeChild_indexPastChildCount_throwsVortexException() {
        // Given a node with no children, as an ALP node stripped of its encoded child would
        // be: the raw children array access must not escape as an AIOOBE
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, null, new ArrayNode[0], new int[0]);
        DecodeContext sut = TestDecodeContexts.of(node, DType.F64).arena(Arena.ofAuto()).build();

        // When / Then
        assertThatThrownBy(() -> sut.decodeChild(0))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("child index 0 out of bounds for 0 child(ren)");
    }

    @Test
    void decodeChildSegment_indexPastChildCount_throwsVortexException() {
        // Given the same childless node reached through the segment overload
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, null, new ArrayNode[0], new int[0]);
        DecodeContext sut = TestDecodeContexts.of(node, DType.F64).arena(Arena.ofAuto()).build();

        // When / Then
        assertThatThrownBy(() -> sut.decodeChildSegment(1, DType.F64, 1))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("child index 1 out of bounds");
    }

    @Test
    void decodeChild_negativeIndex_throwsVortexException() {
        // Given a node with one child but a negative index
        ArrayNode child = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALP, null, new ArrayNode[]{child}, new int[0]);
        DecodeContext sut = TestDecodeContexts.of(node, DType.F64).arena(Arena.ofAuto()).build();

        // When / Then
        assertThatThrownBy(() -> sut.decodeChild(-1, DType.F64, 1))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("child index -1 out of bounds");
    }

    @Test
    void buffer_validIndex_returnsSegment() {
        // Given a node whose buffer index correctly references the single available segment
        MemorySegment segment = MemorySegment.ofArray(new byte[4]);
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        DecodeContext sut = TestDecodeContexts.of(node, DType.UTF8)
                .segments(segment)
                .arena(Arena.ofAuto())
                .build();

        // When
        MemorySegment result = sut.buffer(0);

        // Then
        assertThat(result).isSameAs(segment);
    }

    @Test
    void bufferCount_returnsSegmentBufferLength() {
        // Given two segment buffers
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        DecodeContext sut = TestDecodeContexts.of(node, DType.UTF8)
                .segments(MemorySegment.ofArray(new byte[4]), MemorySegment.ofArray(new byte[4]))
                .arena(Arena.ofAuto())
                .build();

        // When
        int result = sut.bufferCount();

        // Then
        assertThat(result).isEqualTo(2);
    }
}
