package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.ValueLayout;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: encode then decode is lossless for boolean arrays of all lengths (including non-multiples of 8).
class BoolEncodingEncoderTest {

    static Stream<boolean[]> boolArrays() {
        return Stream.of(
                new boolean[]{},
                new boolean[]{false},
                new boolean[]{true},
                new boolean[]{false, true, false, true, false, true, false, true},
                new boolean[]{true, true, true, false, false, false, true, false, true},
                new boolean[]{false, false, false, false, false, false, false},
                new boolean[]{true, true, true, true, true, true, true, true, true}
        );
    }

    @ParameterizedTest
    @MethodSource("boolArrays")
    void encodeDecode_isLossless(boolean[] data) {
        // Given
        var encoder = new BoolEncodingEncoder();
        var decoder = new BoolEncodingDecoder();
        ReadRegistry registry = TestRegistry.ofDecoders(decoder);

        // When
        EncodeResult encoded = encoder.encode(DTypes.BOOL, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.BOOL, registry);
        Array result = decoder.decode(ctx);

        // Then
        assertThat(result).isInstanceOf(BoolArray.class);
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            byte byteVal = ArraySegments.of(result).get(ValueLayout.JAVA_BYTE, i / 8);
            boolean decoded = ((byteVal >>> (i % 8)) & 1) == 1;
            assertThat(decoded).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest
    @MethodSource("boolArrays")
    void encodedSize_isPackedBits(boolean[] data) {
        // Given
        var sut = new BoolEncodingEncoder();

        // When
        EncodeResult encoded = sut.encode(DTypes.BOOL, data, EncodeTestHelper.testCtx());

        // Then — bit-packed: ceiling(n/8) bytes, always ≤ n bytes raw
        long totalBytes = encoded.buffers().stream().mapToLong(java.lang.foreign.MemorySegment::byteSize).sum();
        assertThat(totalBytes).isEqualTo((long) (data.length + 7) / 8);
    }
}
