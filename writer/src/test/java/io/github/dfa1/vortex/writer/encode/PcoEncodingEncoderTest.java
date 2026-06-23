package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.PcoEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.TestRegistry;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Random;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: encode then decode is lossless for all supported pco ptypes.
class PcoEncodingEncoderTest {

    private static final PcoEncodingEncoder ENCODER = new PcoEncodingEncoder();
    private static final PcoEncodingDecoder DECODER = new PcoEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER);

    private static final DType I64 = DType.I64;
    private static final DType U64 = DType.U64;
    private static final DType I32 = DType.I32;
    private static final DType U32 = DType.U32;
    private static final DType I16 = DType.I16;
    private static final DType U16 = DType.U16;
    private static final DType F32 = DType.F32;
    private static final DType F64 = DType.F64;

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
                Arguments.of("multi-chunk", LongStream.range(0, 150_000).toArray()),
                // IntMult: every value × 1000 — triple GCD detects base=1000
                Arguments.of("int-mult-1000", LongStream.range(0, 5000).map(i -> i * 1000).toArray()),
                // IntMult: prices × 100 with small random adjustments
                Arguments.of("int-mult-prices", intMultPriceData(2000))
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
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
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
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
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
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
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
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
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
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_SHORT, (long) i * 2)).as("index %d", i).isEqualTo(data[i]);
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
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_SHORT, (long) i * 2)).as("index %d", i).isEqualTo(data[i]);
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
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_FLOAT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
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
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_DOUBLE, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    /// IntMult-favorable data: base 100 + small random adjustment ([0,100)).
    private static long[] intMultPriceData(int n) {
        Random rng = new Random(42L);
        long[] arr = new long[n];
        for (int i = 0; i < n; i++) {
            arr[i] = (10L + rng.nextInt(1000)) * 100L + rng.nextInt(100);
        }
        return arr;
    }

    // ── seeded-random property sweeps ───────────────────────────────────────────
    //
    // The curated cases above name one corner each; these sweep mixed distributions
    // so the bin optimizer, delta/IntMult mode pickers, and ANS/patch paths face
    // combinations no single example covers. Lengths vary up to a chunk-boundary
    // crossing. Seeds are fixed so failures reproduce.

    static Stream<Arguments> i64Random() {
        Random rng = new Random(0x9C0L);
        Stream.Builder<Arguments> b = Stream.builder();
        for (int t = 0; t < 24; t++) {
            int len = 1 + rng.nextInt(t == 0 ? 70_000 : 4000); // one run crosses the 64K chunk boundary
            long[] a = new long[len];
            int mode = rng.nextInt(6);
            long base = rng.nextLong();
            long mult = 1L + rng.nextInt(1000);
            for (int i = 0; i < len; i++) {
                a[i] = switch (mode) {
                    case 0 -> rng.nextLong();                              // full-range: many bins, noOp
                    case 1 -> rng.nextInt(16);                            // tiny range: tight bins
                    case 2 -> base + i;                                    // monotone: delta
                    case 3 -> base + (long) i * mult + rng.nextInt(8);    // strided + jitter: IntMult
                    case 4 -> (rng.nextInt(20) == 0) ? rng.nextLong() : 1000L + rng.nextInt(4); // sparse outliers: patches/ANS
                    default -> rng.nextInt(3) == 0 ? base : base + rng.nextInt(2); // heavy repeats: runs
                };
            }
            b.add(Arguments.of("i64-mode" + mode + "-len" + len, a));
        }
        return b.build();
    }

    static Stream<Arguments> i32Random() {
        Random rng = new Random(0x9C1L);
        Stream.Builder<Arguments> b = Stream.builder();
        for (int t = 0; t < 24; t++) {
            int len = 1 + rng.nextInt(4000);
            int[] a = new int[len];
            int mode = rng.nextInt(6);
            int base = rng.nextInt();
            int mult = 1 + rng.nextInt(1000);
            for (int i = 0; i < len; i++) {
                a[i] = switch (mode) {
                    case 0 -> rng.nextInt();
                    case 1 -> rng.nextInt(16);
                    case 2 -> base + i;
                    case 3 -> base + i * mult + rng.nextInt(8);
                    case 4 -> (rng.nextInt(20) == 0) ? rng.nextInt() : 1000 + rng.nextInt(4);
                    default -> rng.nextInt(3) == 0 ? base : base + rng.nextInt(2);
                };
            }
            b.add(Arguments.of("i32-mode" + mode + "-len" + len, a));
        }
        return b.build();
    }

    static Stream<Arguments> f64Random() {
        Random rng = new Random(0x9C2L);
        Stream.Builder<Arguments> b = Stream.builder();
        for (int t = 0; t < 20; t++) {
            int len = 1 + rng.nextInt(3000);
            double[] a = new double[len];
            int mode = rng.nextInt(3);
            for (int i = 0; i < len; i++) {
                a[i] = switch (mode) {
                    case 0 -> rng.nextGaussian() * Math.pow(10, rng.nextInt(20) - 10); // wide magnitude
                    case 1 -> 100.0 + (i % 50) * 0.01;                                 // FloatMult-friendly decimals
                    default -> rng.nextInt(8);                                         // low cardinality
                };
            }
            b.add(Arguments.of("f64-mode" + mode + "-len" + len, a));
        }
        return b.build();
    }

    static Stream<Arguments> f32Random() {
        Random rng = new Random(0x9C3L);
        Stream.Builder<Arguments> b = Stream.builder();
        for (int t = 0; t < 20; t++) {
            int len = 1 + rng.nextInt(3000);
            float[] a = new float[len];
            int mode = rng.nextInt(3);
            for (int i = 0; i < len; i++) {
                a[i] = switch (mode) {
                    case 0 -> (float) (rng.nextGaussian() * Math.pow(10, rng.nextInt(12) - 6));
                    case 1 -> 100.0f + (i % 50) * 0.01f;
                    default -> rng.nextInt(8);
                };
            }
            b.add(Arguments.of("f32-mode" + mode + "-len" + len, a));
        }
        return b.build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("i64Random")
    void encodeDecode_i64_random_isLossless(String name, long[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(I64, data, EncodeTestHelper.testCtx());

        // When
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, I64, REGISTRY);
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        MemorySegment m = result.materialize(Arena.ofAuto());
        for (int i = 0; i < data.length; i++) {
            assertThat(m.get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("i32Random")
    void encodeDecode_i32_random_isLossless(String name, int[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(I32, data, EncodeTestHelper.testCtx());

        // When
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, I32, REGISTRY);
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        MemorySegment m = result.materialize(Arena.ofAuto());
        for (int i = 0; i < data.length; i++) {
            assertThat(m.get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("f64Random")
    void encodeDecode_f64_random_isLossless(String name, double[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(F64, data, EncodeTestHelper.testCtx());

        // When
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, F64, REGISTRY);
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        MemorySegment m = result.materialize(Arena.ofAuto());
        for (int i = 0; i < data.length; i++) {
            assertThat(m.get(PTypeIO.LE_DOUBLE, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("f32Random")
    void encodeDecode_f32_random_isLossless(String name, float[] data) {
        // Given
        EncodeResult encoded = ENCODER.encode(F32, data, EncodeTestHelper.testCtx());

        // When
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, F32, REGISTRY);
        Array result = DECODER.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(data.length);
        MemorySegment m = result.materialize(Arena.ofAuto());
        for (int i = 0; i < data.length; i++) {
            assertThat(m.get(PTypeIO.LE_FLOAT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
        }
    }
}
