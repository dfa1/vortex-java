package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.reader.ReadRegistry;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LazyByteBoolArray;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.core.model.EncodingId;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByteBoolEncodingDecoderTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("all false", new byte[]{0, 0, 0}, new boolean[]{false, false, false}),
                Arguments.of("all true", new byte[]{1, 42, (byte) 0xFF}, new boolean[]{true, true, true}),
                Arguments.of("mixed", new byte[]{0, 1, 0, 1}, new boolean[]{false, true, false, true}),
                Arguments.of("empty", new byte[]{}, new boolean[]{})
        );
    }

    private static DecodeContext buildCtx(byte[] byteValues) {
        MemorySegment buf = MemorySegment.ofArray(byteValues);
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_BYTEBOOL, null, new ArrayNode[0], new int[]{0});
        ReadRegistry registry = TestRegistry.ofDecoders(new ByteBoolEncodingDecoder());
        return new DecodeContext(node, DTypes.BOOL, byteValues.length, new MemorySegment[]{buf}, registry,
                Arena.ofAuto());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void decode_byteBool_readsBytesInPlace(String name, byte[] input, boolean[] expected) {
        // Given
        DecodeContext ctx = buildCtx(input);
        var sut = new ByteBoolEncodingDecoder();

        // When
        var result = sut.decode(ctx);

        // Then — the concrete type is asserted because the values alone would also pass on the
        // eager bit-packing path this replaced
        assertThat(result).isInstanceOf(LazyByteBoolArray.class);
        assertThat(result.length()).isEqualTo(expected.length);
        BoolArray boolArr = (BoolArray) result;
        for (int i = 0; i < expected.length; i++) {
            assertThat(boolArr.getBoolean(i)).as("index %d", i).isEqualTo(expected[i]);
        }
    }

    /// The buffer is untrusted and holds one byte per row, so a shorter one is malformed. The
    /// eager packing loop faulted on whichever row ran off the end, as a raw
    /// `IndexOutOfBoundsException`; the reader must fail as a [VortexException] (ADR 0003), and
    /// checking once at decode also keeps the carrier's accessor free of a per-row bound.
    @Test
    void decode_bufferShorterThanRowCount_throws() {
        // Given — 3 bytes of data but 8 declared rows
        MemorySegment buf = MemorySegment.ofArray(new byte[]{1, 0, 1});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_BYTEBOOL, null, new ArrayNode[0], new int[]{0});
        ReadRegistry registry = TestRegistry.ofDecoders(new ByteBoolEncodingDecoder());
        DecodeContext ctx = new DecodeContext(node, DTypes.BOOL, 8, new MemorySegment[]{buf}, registry,
                Arena.ofAuto());
        var sut = new ByteBoolEncodingDecoder();

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("shorter than the 8 declared row(s)");
    }

    /// Rust's ByteBool takes `[validity?]` as its only child. The decoder used to ignore it, so
    /// every null row of a Rust-written nullable column read back as `false`.
    @Test
    void decode_validityChild_masksNullRows() {
        // Given true, null, true: validity bits 0b101
        ArrayNode validityNode = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_BYTEBOOL, null, new ArrayNode[]{validityNode}, new int[]{0});
        DecodeContext ctx = new DecodeContext(node, new DType.Bool(true), 3,
                new MemorySegment[]{MemorySegment.ofArray(new byte[]{1, 0, 1}), MemorySegment.ofArray(new byte[]{0b101})},
                TestRegistry.ofDecoders(new ByteBoolEncodingDecoder(), new BoolEncodingDecoder()), Arena.ofAuto());
        var sut = new ByteBoolEncodingDecoder();

        // When
        var result = sut.decode(ctx);

        // Then
        MaskedArray masked = (MaskedArray) result;
        assertThat(masked.isValid(0)).isTrue();
        assertThat(masked.isValid(1)).isFalse();
        assertThat(masked.isValid(2)).isTrue();
        assertThat(((BoolArray) masked.inner()).getBoolean(2)).isTrue();
    }

    @Test
    void decode_twoChildren_throws() {
        // Given Rust accepts only [] or [validity]
        ArrayNode validityNode = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_BYTEBOOL, null,
                new ArrayNode[]{validityNode, validityNode}, new int[]{0});
        DecodeContext ctx = new DecodeContext(node, new DType.Bool(true), 1,
                new MemorySegment[]{MemorySegment.ofArray(new byte[]{1}), MemorySegment.ofArray(new byte[]{1})},
                TestRegistry.ofDecoders(new ByteBoolEncodingDecoder(), new BoolEncodingDecoder()), Arena.ofAuto());
        var sut = new ByteBoolEncodingDecoder();

        // When / Then
        assertThatThrownBy(() -> sut.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected 0 or 1 children, got 2");
    }
}
