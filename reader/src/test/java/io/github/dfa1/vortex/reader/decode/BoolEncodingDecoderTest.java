package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BoolEncodingDecoderTest {

    // Bit-packs a boolean[] LSB-first into a byte[]: bit i is at byte[i/8] bit (i%8).
    private static MemorySegment packBits(boolean[] values) {
        byte[] bytes = new byte[(values.length + 7) / 8];
        for (int i = 0; i < values.length; i++) {
            if (values[i]) {
                bytes[i / 8] |= (byte) (1 << (i % 8));
            }
        }
        return MemorySegment.ofArray(bytes);
    }

    // Builds a context with no validity child (non-nullable bool).
    private static DecodeContext nonNullableCtx(boolean[] values) {
        MemorySegment bits = packBits(values);
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{0});
        ReadRegistry registry = TestRegistry.ofDecoders(new BoolEncodingDecoder());
        return new DecodeContext(node, DTypes.BOOL, values.length, new MemorySegment[]{bits}, registry, Arena.ofAuto());
    }

    // Builds a context with a vortex.bool validity child (nullable bool).
    // The validity child is itself bit-packed; false = null row.
    private static DecodeContext nullableCtx(boolean[] values, boolean[] valid) {
        MemorySegment bits = packBits(values);
        MemorySegment validBits = packBits(valid);
        ArrayNode validityNode = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[]{validityNode}, new int[]{0});
        ReadRegistry registry = TestRegistry.ofDecoders(new BoolEncodingDecoder());
        return new DecodeContext(node, DTypes.BOOL_N, values.length,
                new MemorySegment[]{bits, validBits}, registry, Arena.ofAuto());
    }

    static Stream<Arguments> nonNullableCases() {
        return Stream.of(
                Arguments.of("empty", new boolean[]{}),
                Arguments.of("all false", new boolean[]{false, false, false}),
                Arguments.of("all true", new boolean[]{true, true, true}),
                Arguments.of("mixed", new boolean[]{true, false, true, true, false}),
                // Crosses a byte boundary: bit 7 is in byte 0, bit 8 is in byte 1.
                Arguments.of("byte boundary", new boolean[]{false, false, false, false, false, false, false, true, false})
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonNullableCases")
    void decode_nonNullable_returnsBoolArray(String name, boolean[] values) {
        // Given
        DecodeContext ctx = nonNullableCtx(values);
        var sut = new BoolEncodingDecoder();

        // When
        var result = sut.decode(ctx);

        // Then — non-nullable path returns a plain BoolArray, not a MaskedArray
        assertThat(result).isInstanceOf(BoolArray.class);
        assertThat(result).isNotInstanceOf(MaskedArray.class);
        assertThat(result.length()).isEqualTo(values.length);
        BoolArray boolArr = (BoolArray) result;
        for (int i = 0; i < values.length; i++) {
            assertThat(boolArr.getBoolean(i)).as("index %d", i).isEqualTo(values[i]);
        }
    }

    @Test
    void decode_nullable_returnsMaskedArray_withNullsHidingUnderlying() {
        // Given — rows 0,2 are valid; row 1 is null. The value bits at null positions are
        // irrelevant, but a non-zero bit is stored there to confirm the decoder ignores them.
        boolean[] values = {true, true, false};
        boolean[] valid = {true, false, true};
        DecodeContext ctx = nullableCtx(values, valid);
        var sut = new BoolEncodingDecoder();

        // When
        var result = sut.decode(ctx);

        // Then — nullable path wraps in MaskedArray; null rows report isValid=false.
        assertThat(result).isInstanceOf(MaskedArray.class);
        MaskedArray masked = (MaskedArray) result;
        assertThat(masked.length()).isEqualTo(3);
        assertThat(masked.isValid(0)).isTrue();
        assertThat(masked.isValid(1)).isFalse();
        assertThat(masked.isValid(2)).isTrue();
        // Valid rows deliver the correct boolean value via the inner BoolArray.
        BoolArray inner = (BoolArray) masked.inner();
        assertThat(inner.getBoolean(0)).isTrue();
        assertThat(inner.getBoolean(2)).isFalse();
    }

    @Test
    void decode_nullable_allNulls_allRowsInvalid() {
        // Given — every validity bit is false; values buffer content does not matter
        boolean[] values = {false, false, false};
        boolean[] valid = {false, false, false};
        DecodeContext ctx = nullableCtx(values, valid);
        var sut = new BoolEncodingDecoder();

        // When
        var result = sut.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(MaskedArray.class);
        MaskedArray masked = (MaskedArray) result;
        for (int i = 0; i < 3; i++) {
            assertThat(masked.isValid(i)).as("row %d must be null", i).isFalse();
        }
    }
}
