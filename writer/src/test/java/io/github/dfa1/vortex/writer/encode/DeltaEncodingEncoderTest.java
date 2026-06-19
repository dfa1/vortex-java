package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.DeltaMetadata;
import io.github.dfa1.vortex.reader.decode.DeltaEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.MemorySegment;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DeltaEncodingEncoderTest {

    private static final DeltaEncodingEncoder ENCODER = new DeltaEncodingEncoder();
    private static final DeltaEncodingDecoder DECODER = new DeltaEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    static Stream<long[]> i64Arrays() {
        return Stream.of(
                new long[]{0},
                new long[]{Long.MIN_VALUE},
                new long[]{0, 1, 2, 3, 4, 5, 6, 7},
                new long[]{100, 200, 300, 400, 500},
                new long[]{-100, -50, 0, 50, 100},
                new long[]{1000, 999, 998, 997, 996}
        );
    }

    static Stream<int[]> i32Arrays() {
        return Stream.of(
                new int[]{0},
                new int[]{Integer.MIN_VALUE},
                new int[]{0, 1, 2, 3, 4, 5, 6, 7},
                new int[]{10, 20, 30, 40, 50},
                new int[]{-5, -4, -3, -2, -1, 0}
        );
    }

    static Stream<Arguments> monotoneI64Arrays() {
        return Stream.of(
                Arguments.of("ascending-1", new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}),
                Arguments.of("ascending-100", new long[]{0, 100, 200, 300, 400, 500, 600, 700, 800, 900}),
                Arguments.of("descending", new long[]{1000, 999, 998, 997, 996, 995, 994, 993, 992, 991})
        );
    }

    @ParameterizedTest
    @MethodSource("i64Arrays")
    void encodeDecode_i64_isLossless(long[] data) {
        // Given
        EncodeResult resultEncoded = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, data.length, DTypes.I64, REGISTRY);

        // When
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest
    @MethodSource("i32Arrays")
    void encodeDecode_i32_isLossless(int[] data) {
        // Given
        EncodeResult resultEncoded = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, data.length, DTypes.I32, REGISTRY);

        // When
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("monotoneI64Arrays")
    void encodeDecode_monotoneI64_isLossless(String name, long[] data) {
        // Given
        EncodeResult resultEncoded = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, data.length, DTypes.I64, REGISTRY);

        // When
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @Test
    void encode_i64_metadata_deltasLen_isNonZero() throws Exception {
        // Given
        long[] data = {10L, 20L, 30L, 40L, 50L};

        // When
        EncodeResult result = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
        MemorySegment metaSeg = MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
        DeltaMetadata meta = DeltaMetadata.decode(metaSeg, 0, metaSeg.byteSize());

        // Then
        assertThat(meta.deltas_len()).isGreaterThan(0);
    }
}
