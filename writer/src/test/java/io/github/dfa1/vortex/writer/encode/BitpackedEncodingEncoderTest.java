package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.BitPackedMetadata;
import io.github.dfa1.vortex.reader.decode.BitpackedEncodingDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: encode then decode is lossless for unsigned integer types.
class BitpackedEncodingEncoderTest {

    private static final BitpackedEncodingEncoder ENCODER = new BitpackedEncodingEncoder();
    private static final BitpackedEncodingDecoder DECODER = new BitpackedEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER);

    static Stream<Arguments> u32Arrays() {
        return Stream.of(
                Arguments.of("empty", new int[]{}),
                Arguments.of("single", new int[]{0}),
                Arguments.of("all-zeros", new int[]{0, 0, 0, 0, 0}),
                Arguments.of("small-values", new int[]{1, 2, 3, 4, 5, 6, 7}),
                Arguments.of("mixed", new int[]{0, 7, 63, 255, 1023, 65535}),
                Arguments.of("max-unsigned", new int[]{-1, -1, -1}) // 0xFFFFFFFF
        );
    }

    static Stream<Arguments> u64Arrays() {
        return Stream.of(
                Arguments.of("empty", new long[]{}),
                Arguments.of("single", new long[]{0L}),
                Arguments.of("small-values", new long[]{1L, 2L, 3L, 4L, 5L}),
                Arguments.of("large-values", new long[]{0L, 0xFFFFL, 0xFFFFFFL, 0xFFFFFFFFL})
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("u32Arrays")
    void encodeDecode_u32_isLossless(String name, int[] data) {
        EncodeResult encoded = ENCODER.encode(DTypes.U32, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.U32, REGISTRY);
        Array result = DECODER.decode(ctx);

        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("u64Arrays")
    void encodeDecode_u64_isLossless(String name, long[] data) {
        EncodeResult encoded = ENCODER.encode(DTypes.U64, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.U64, REGISTRY);
        Array result = DECODER.decode(ctx);

        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @Test
    void encode_i32_metadata_bitWidth_isNonZero() throws Exception {
        // Given — max value 5 needs 3 bits; if tag drifts, bit_width reads as 0 (proto3 default)
        int[] data = {1, 2, 3, 4, 5};

        EncodeResult result = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
        var metaSeg = java.lang.foreign.MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
        BitPackedMetadata meta = BitPackedMetadata.decode(metaSeg, 0, metaSeg.byteSize());

        assertThat(meta.bit_width()).isGreaterThan(0);
    }
}
