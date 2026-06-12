package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.reader.ReadRegistry;

import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.encoding.EncodingId;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

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
        ArrayNode node = ArrayNode.of(EncodingId.VORTEX_BYTEBOOL, null, new ArrayNode[0], new int[]{0}, null);
        ReadRegistry registry = TestRegistry.ofDecoders(new ByteBoolEncodingDecoder());
        return new DecodeContext(node, DTypes.BOOL, byteValues.length, new MemorySegment[]{buf}, registry,
                Arena.ofAuto());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void decode_byteBool_packsToBitArray(String name, byte[] input, boolean[] expected) {
        // Given
        DecodeContext ctx = buildCtx(input);
        var sut = new ByteBoolEncodingDecoder();

        // When
        var result = sut.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(BoolArray.class);
        assertThat(result.length()).isEqualTo(expected.length);
        BoolArray boolArr = (BoolArray) result;
        for (int i = 0; i < expected.length; i++) {
            assertThat(boolArr.getBoolean(i)).as("index %d", i).isEqualTo(expected[i]);
        }
    }
}
