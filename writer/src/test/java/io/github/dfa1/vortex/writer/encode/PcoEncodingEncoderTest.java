package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.PcoEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.TestRegistry;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: encode then decode is lossless for all supported pco ptypes.
class PcoEncodingEncoderTest {

    private static final PcoEncodingEncoder ENCODER = new PcoEncodingEncoder();
    private static final PcoEncodingDecoder DECODER = new PcoEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER);

    private static final DType I64 = new DType.Primitive(PType.I64, false);
    private static final DType U64 = new DType.Primitive(PType.U64, false);
    private static final DType I32 = new DType.Primitive(PType.I32, false);
    private static final DType U32 = new DType.Primitive(PType.U32, false);
    private static final DType I16 = new DType.Primitive(PType.I16, false);
    private static final DType U16 = new DType.Primitive(PType.U16, false);
    private static final DType F32 = new DType.Primitive(PType.F32, false);
    private static final DType F64 = new DType.Primitive(PType.F64, false);

    static Stream<Arguments> i64Arrays() {
        return Stream.of(
                Arguments.of("empty", new long[]{}),
                Arguments.of("single", new long[]{42L}),
                Arguments.of("all-same", new long[]{7L, 7L, 7L}),
                Arguments.of("sequential", new long[]{0L, 1L, 2L, 3L, 4L, 5L}),
                Arguments.of("negative", new long[]{-3L, -2L, -1L, 0L, 1L, 2L, 3L}),
                Arguments.of("min-max", new long[]{Long.MIN_VALUE, Long.MAX_VALUE}),
                // delta path: large sequential ranges where delta beats noOp
                Arguments.of("sequential-1k", LongStream.range(0, 1000).toArray()),
                Arguments.of("stride-4", LongStream.range(0, 500).map(i -> i * 4).toArray()),
                Arguments.of("sequential-negative", LongStream.range(-500, 0).toArray()),
                // multi-chunk: crosses the 64K-element chunk boundary
                Arguments.of("multi-chunk", LongStream.range(0, 150_000).toArray())
        );
    }

    static Stream<Arguments> u64Arrays() {
        return Stream.of(
                Arguments.of("empty", new long[]{}),
                Arguments.of("single", new long[]{0L}),
                Arguments.of("sequential", new long[]{0L, 1L, 2L, 3L}),
                Arguments.of("large-unsigned", new long[]{-1L, -2L, -3L}) // 0xFFFF..FF etc.
        );
    }

    static Stream<Arguments> i32Arrays() {
        return Stream.of(
                Arguments.of("empty", new int[]{}),
                Arguments.of("single", new int[]{100}),
                Arguments.of("all-same", new int[]{0, 0, 0}),
                Arguments.of("sequential", new int[]{1, 2, 3, 4, 5}),
                Arguments.of("negative", new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}),
                Arguments.of("sequential-1k", LongStream.range(0, 1000).mapToInt(i -> (int) i).toArray())
        );
    }

    static Stream<Arguments> u32Arrays() {
        return Stream.of(
                Arguments.of("empty", new int[]{}),
                Arguments.of("single", new int[]{0}),
                Arguments.of("mixed", new int[]{0, 1, 255, 65535, -1}) // -1 = 0xFFFFFFFF unsigned
        );
    }

    static Stream<Arguments> i16Arrays() {
        return Stream.of(
                Arguments.of("empty", new short[]{}),
                Arguments.of("single", new short[]{(short) 1000}),
                Arguments.of("sequential", new short[]{-3, -2, -1, 0, 1, 2, 3}),
                Arguments.of("min-max", new short[]{Short.MIN_VALUE, Short.MAX_VALUE})
        );
    }

    static Stream<Arguments> u16Arrays() {
        return Stream.of(
                Arguments.of("empty", new short[]{}),
                Arguments.of("single", new short[]{(short) 0xFFFF}),
                Arguments.of("sequential", new short[]{0, 1, 2, 3, 4})
        );
    }

    static Stream<Arguments> f32Arrays() {
        return Stream.of(
                Arguments.of("empty", new float[]{}),
                Arguments.of("single", new float[]{1.0f}),
                Arguments.of("mixed-sign", new float[]{-1.5f, 0.0f, 1.5f, 2.5f}),
                Arguments.of("sequential", new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f})
        );
    }

    static Stream<Arguments> f64Arrays() {
        return Stream.of(
                Arguments.of("empty", new double[]{}),
                Arguments.of("single", new double[]{3.14}),
                Arguments.of("mixed-sign", new double[]{-1.0, 0.0, 1.0, 2.0}),
                Arguments.of("sequential", new double[]{1.0, 2.0, 3.0, 4.0, 5.0})
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("i64Arrays")
    void encodeDecode_i64_isLossless(String name, long[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(I64, data, EncodeTestHelper.testCtx());

        // When
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, I64, REGISTRY);
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("u64Arrays")
    void encodeDecode_u64_isLossless(String name, long[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(U64, data, EncodeTestHelper.testCtx());

        // When
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, U64, REGISTRY);
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("i32Arrays")
    void encodeDecode_i32_isLossless(String name, int[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(I32, data, EncodeTestHelper.testCtx());

        // When
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, I32, REGISTRY);
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("u32Arrays")
    void encodeDecode_u32_isLossless(String name, int[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(U32, data, EncodeTestHelper.testCtx());

        // When
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, U32, REGISTRY);
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("i16Arrays")
    void encodeDecode_i16_isLossless(String name, short[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(I16, data, EncodeTestHelper.testCtx());

        // When
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, I16, REGISTRY);
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_SHORT, (long) i * 2)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("u16Arrays")
    void encodeDecode_u16_isLossless(String name, short[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(U16, data, EncodeTestHelper.testCtx());

        // When
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, U16, REGISTRY);
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_SHORT, (long) i * 2)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("f32Arrays")
    void encodeDecode_f32_isLossless(String name, float[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(F32, data, EncodeTestHelper.testCtx());

        // When
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, F32, REGISTRY);
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_FLOAT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("f64Arrays")
    void encodeDecode_f64_isLossless(String name, double[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(F64, data, EncodeTestHelper.testCtx());

        // When
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, F64, REGISTRY);
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
        }
    }
}
