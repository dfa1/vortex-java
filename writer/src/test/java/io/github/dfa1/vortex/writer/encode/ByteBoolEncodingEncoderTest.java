package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.reader.decode.ByteBoolEncodingDecoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ByteBoolEncodingEncoderTest {

    static Stream<boolean[]> boolArrays() {
        return Stream.of(
                new boolean[]{},
                new boolean[]{false},
                new boolean[]{true},
                new boolean[]{true, false, true, false, true},
                new boolean[]{false, false, false, false},
                new boolean[]{true, true, true, true, true, true, true, true, true}
        );
    }

    @ParameterizedTest
    @MethodSource("boolArrays")
    void encodeDecode_isLossless(boolean[] data) {
        // Given
        var encoder = new ByteBoolEncodingEncoder();
        var decoder = new ByteBoolEncodingDecoder();
        ReadRegistry registry = TestRegistry.ofDecoders(decoder);

        // When
        EncodeResult encoded = encoder.encode(DTypes.BOOL, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.BOOL, registry);
        Array result = decoder.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        BoolArray boolArr = (BoolArray) result;
        for (int i = 0; i < data.length; i++) {
            assertThat(boolArr.getBoolean(i)).as("index %d", i).isEqualTo(data[i]);
        }
    }
}
