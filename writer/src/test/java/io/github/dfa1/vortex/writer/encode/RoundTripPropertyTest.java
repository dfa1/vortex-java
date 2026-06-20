package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.decode.AlpRdEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.BitpackedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.DeltaEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.EncodingDecoder;
import io.github.dfa1.vortex.reader.decode.FrameOfReferenceEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.reader.decode.ZigZagEncodingDecoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property-based `decode(encode(x)) == x` round-trips for the lossless transform encodings.
///
/// Seeded random inputs (plus curated edges: 0, ±1, MIN/MAX, near-extremes) flow through the
/// real encoder → the writer's `EncodeResult` → the reader's decoder, asserting exact identity.
/// Integer encodings (Delta, FrameOfReference, ZigZag) are checked for value identity including
/// the wrap-around cases where the internal subtraction/shift overflows; AlpRd (the real-double
/// bit-split codec) is checked **bit-exact** since it stores the full IEEE-754 content.
class RoundTripPropertyTest {

    private static final DeltaEncodingEncoder DELTA = new DeltaEncodingEncoder();
    private static final FrameOfReferenceEncodingEncoder FOR = new FrameOfReferenceEncodingEncoder();
    private static final ZigZagEncodingEncoder ZIGZAG = new ZigZagEncodingEncoder();
    private static final AlpRdEncodingEncoder ALPRD = new AlpRdEncodingEncoder();

    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            new DeltaEncodingDecoder(), new FrameOfReferenceEncodingDecoder(),
            new ZigZagEncodingDecoder(), new AlpRdEncodingDecoder(),
            new BitpackedEncodingDecoder(), new PrimitiveEncodingDecoder());

    private static Array roundTrip(EncodingEncoder encoder, EncodingDecoder decoder, DType dtype, Object data, int n) {
        EncodeResult enc = encoder.encode(dtype, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(enc, n, dtype, REGISTRY);
        return decoder.decode(ctx);
    }

    // ── Delta ──────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("longArrays")
    void delta_i64(long[] data) {
        assertLongs(roundTrip(DELTA, new DeltaEncodingDecoder(), DTypes.I64, data, data.length), data);
    }

    @ParameterizedTest
    @MethodSource("intArrays")
    void delta_i32(int[] data) {
        assertInts(roundTrip(DELTA, new DeltaEncodingDecoder(), DTypes.I32, data, data.length), data);
    }

    // ── FrameOfReference ───────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("longArrays")
    void frameOfReference_i64(long[] data) {
        assertLongs(roundTrip(FOR, new FrameOfReferenceEncodingDecoder(), DTypes.I64, data, data.length), data);
    }

    @ParameterizedTest
    @MethodSource("intArrays")
    void frameOfReference_i32(int[] data) {
        assertInts(roundTrip(FOR, new FrameOfReferenceEncodingDecoder(), DTypes.I32, data, data.length), data);
    }

    // ── ZigZag ─────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("longArrays")
    void zigzag_i64(long[] data) {
        assertLongs(roundTrip(ZIGZAG, new ZigZagEncodingDecoder(), DTypes.I64, data, data.length), data);
    }

    @ParameterizedTest
    @MethodSource("intArrays")
    void zigzag_i32(int[] data) {
        assertInts(roundTrip(ZIGZAG, new ZigZagEncodingDecoder(), DTypes.I32, data, data.length), data);
    }

    // ── AlpRd (bit-exact) ──────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("doubleArrays")
    void alpRd_f64_bitExact(double[] data) {
        DoubleArray r = (DoubleArray) roundTrip(ALPRD, new AlpRdEncodingDecoder(), DTypes.F64, data, data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(canon(r.getDouble(i))).as("index %d value %s", i, data[i]).isEqualTo(canon(data[i]));
        }
    }

    @ParameterizedTest
    @MethodSource("floatArrays")
    void alpRd_f32_bitExact(float[] data) {
        FloatArray r = (FloatArray) roundTrip(ALPRD, new AlpRdEncodingDecoder(), DTypes.F32, data, data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(canon(r.getFloat(i))).as("index %d value %s", i, data[i]).isEqualTo(canon(data[i]));
        }
    }

    // ── assertions ─────────────────────────────────────────────────────────────

    private static void assertLongs(Array result, long[] expected) {
        LongArray a = (LongArray) result;
        for (int i = 0; i < expected.length; i++) {
            assertThat(a.getLong(i)).as("index %d", i).isEqualTo(expected[i]);
        }
    }

    private static void assertInts(Array result, int[] expected) {
        IntArray a = (IntArray) result;
        for (int i = 0; i < expected.length; i++) {
            assertThat(a.getInt(i)).as("index %d", i).isEqualTo(expected[i]);
        }
    }

    private static long canon(double d) {
        return d == 0.0 ? 0L : Double.doubleToRawLongBits(d); // ±0 collapse, matches ALP family
    }

    private static int canon(float f) {
        return f == 0.0f ? 0 : Float.floatToRawIntBits(f);
    }

    // ── generators (seeded) ────────────────────────────────────────────────────

    static Stream<long[]> longArrays() {
        Random rng = new Random(0xD17AL);
        Stream.Builder<long[]> b = Stream.builder();
        b.add(new long[]{0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1});
        for (int n = 0; n < 50; n++) {
            int len = 1 + rng.nextInt(80);
            long[] a = new long[len];
            for (int i = 0; i < len; i++) {
                a[i] = switch (rng.nextInt(3)) {
                    case 0 -> rng.nextLong();                       // full-range: forces wrap-around
                    case 1 -> (long) (rng.nextInt(2001) - 1000);   // small near-constant: clean delta/FoR
                    default -> 1_000_000L + rng.nextInt(1000);     // tight cluster around a reference
                };
            }
            b.add(a);
        }
        return b.build();
    }

    static Stream<int[]> intArrays() {
        Random rng = new Random(0xD17BL);
        Stream.Builder<int[]> b = Stream.builder();
        b.add(new int[]{0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1});
        for (int n = 0; n < 50; n++) {
            int len = 1 + rng.nextInt(80);
            int[] a = new int[len];
            for (int i = 0; i < len; i++) {
                a[i] = switch (rng.nextInt(3)) {
                    case 0 -> rng.nextInt();
                    case 1 -> rng.nextInt(2001) - 1000;
                    default -> 1_000_000 + rng.nextInt(1000);
                };
            }
            b.add(a);
        }
        return b.build();
    }

    static Stream<double[]> doubleArrays() {
        Random rng = new Random(0xA1F64L);
        Stream.Builder<double[]> b = Stream.builder();
        b.add(new double[]{0.0, -0.0, 1.0, -1.0, 0.5, -0.25, 3.14159,
                Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_NORMAL, 1e-300, 1e300,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY});
        for (int n = 0; n < 50; n++) {
            int len = 1 + rng.nextInt(80);
            double[] a = new double[len];
            for (int i = 0; i < len; i++) {
                a[i] = rng.nextInt(2) == 0
                        ? Double.longBitsToDouble(rng.nextLong())
                        : rng.nextGaussian() * Math.pow(10, rng.nextInt(20) - 10);
            }
            b.add(a);
        }
        return b.build();
    }

    static Stream<float[]> floatArrays() {
        Random rng = new Random(0xA1F32L);
        Stream.Builder<float[]> b = Stream.builder();
        b.add(new float[]{0.0f, -0.0f, 1.0f, -1.0f, 0.5f, -0.25f, 3.14159f,
                Float.MIN_VALUE, Float.MAX_VALUE, Float.MIN_NORMAL, 1e-30f, 1e30f,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY});
        for (int n = 0; n < 50; n++) {
            int len = 1 + rng.nextInt(80);
            float[] a = new float[len];
            for (int i = 0; i < len; i++) {
                a[i] = rng.nextInt(2) == 0
                        ? Float.intBitsToFloat(rng.nextInt())
                        : (float) (rng.nextGaussian() * Math.pow(10, rng.nextInt(12) - 6));
            }
            b.add(a);
        }
        return b.build();
    }
}
