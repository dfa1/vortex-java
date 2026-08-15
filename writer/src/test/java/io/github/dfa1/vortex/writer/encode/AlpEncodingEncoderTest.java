package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoALPMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.reader.decode.AlpEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AlpEncodingEncoderTest {

    private static final AlpEncodingEncoder ENCODER = new AlpEncodingEncoder();
    private static final AlpEncodingDecoder DECODER = new AlpEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    @Nested
    class Decode {

        private static DecodeContext buildAlpCtxF64(
                int expE, int expF, long[] encodedVals,
                long[] patchIndices, double[] patchValues
        ) {
            ProtoPatchesMetadata pm = patchIndices != null
                    ? new ProtoPatchesMetadata((long) patchIndices.length, 0L,
                            io.github.dfa1.vortex.core.proto.ProtoPType.U32, null, null, null)
                    : null;
            byte[] metaBytes = new ProtoALPMetadata(expE, expF, pm).encode();

            byte[] encBuf = new byte[encodedVals.length * 8];
            ByteBuffer bb = ByteBuffer.wrap(encBuf).order(ByteOrder.LITTLE_ENDIAN);
            for (long v : encodedVals) {
                bb.putLong(v);
            }

            ArrayNode encNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{0});

            MemorySegment[] segments;
            ArrayNode[] children;

            if (patchIndices != null) {
                byte[] idxBuf = new byte[patchIndices.length * 4];
                ByteBuffer ib = ByteBuffer.wrap(idxBuf).order(ByteOrder.LITTLE_ENDIAN);
                for (long v : patchIndices) {
                    ib.putInt((int) v);
                }
                byte[] valBuf = new byte[patchValues.length * 8];
                ByteBuffer vb = ByteBuffer.wrap(valBuf).order(ByteOrder.LITTLE_ENDIAN);
                for (double v : patchValues) {
                    vb.putDouble(v);
                }
                ArrayNode idxNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
                ArrayNode valNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{2});
                children = new ArrayNode[]{encNode, idxNode, valNode};
                segments = new MemorySegment[]{
                        MemorySegment.ofArray(encBuf), MemorySegment.ofArray(idxBuf), MemorySegment.ofArray(valBuf)};
            } else {
                children = new ArrayNode[]{encNode};
                segments = new MemorySegment[]{MemorySegment.ofArray(encBuf)};
            }

            ArrayNode alpNode = new ArrayNode(EncodingId.VORTEX_ALP,
                    MemorySegment.ofArray(metaBytes), children, new int[0]);

            return new DecodeContext(alpNode, DTypes.F64, encodedVals.length, segments, REGISTRY, java.lang.foreign.Arena.global());
        }

        private static DecodeContext buildAlpCtxF32(int expE, int expF, int[] encodedVals) {
            byte[] metaBytes = new ProtoALPMetadata(expE, expF, null).encode();
            byte[] encBuf = new byte[encodedVals.length * 4];
            ByteBuffer bb = ByteBuffer.wrap(encBuf).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : encodedVals) {
                bb.putInt(v);
            }
            ArrayNode encNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
            ArrayNode alpNode = new ArrayNode(EncodingId.VORTEX_ALP, MemorySegment.ofArray(metaBytes),
                    new ArrayNode[]{encNode}, new int[0]);
            MemorySegment[] segments = {MemorySegment.ofArray(encBuf)};
            return new DecodeContext(alpNode, DTypes.F32, encodedVals.length, segments, REGISTRY, java.lang.foreign.Arena.global());
        }

        @Test
        void decode_f64_noPatches() {
            // Given
            int expE = 2, expF = 0;
            long[] encoded = {123L, 456L, 789L};
            double[] expected = {1.23, 4.56, 7.89};
            DecodeContext ctx = buildAlpCtxF64(expE, expF, encoded, null, null);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(encoded.length);
            DoubleArray arr = (DoubleArray) result;
            for (int i = 0; i < expected.length; i++) {
                assertThat(arr.getDouble(i)).as("index %d", i).isCloseTo(expected[i], within(1e-9));
            }
        }

        @Test
        void decode_f64_withPatches() {
            // Given
            int expE = 2, expF = 0;
            long[] encoded = {100L, 0L, 200L, 0L, 300L};
            long[] patchIndices = {1L, 3L};
            double[] patchValues = {Double.NaN, Double.POSITIVE_INFINITY};
            DecodeContext ctx = buildAlpCtxF64(expE, expF, encoded, patchIndices, patchValues);

            // When
            DoubleArray result = (DoubleArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getDouble(0)).isCloseTo(1.0, within(1e-9));
            assertThat(result.getDouble(1)).isNaN();
            assertThat(result.getDouble(2)).isCloseTo(2.0, within(1e-9));
            assertThat(result.getDouble(3)).isInfinite();
            assertThat(result.getDouble(4)).isCloseTo(3.0, within(1e-9));
        }

        @ParameterizedTest
        @CsvSource({"0,0", "1,0", "2,1", "3,2", "4,3"})
        void decode_f64_exponentCombinations(int expE, int expF) {
            // Given
            double value = 42.0;
            double[] f10 = {1e0, 1e1, 1e2, 1e3, 1e4};
            double[] if10 = {1e-0, 1e-1, 1e-2, 1e-3, 1e-4};
            long encVal = Math.round(value * f10[expE] * if10[expF]);
            long[] encoded = {encVal};
            DecodeContext ctx = buildAlpCtxF64(expE, expF, encoded, null, null);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(((DoubleArray) result).getDouble(0)).isCloseTo(value, within(1e-6));
        }

        @Test
        void decode_f32_noPatches() {
            // Given
            int expE = 1, expF = 0;
            int[] encoded = {10, 25, 100};
            float[] expected = {1.0f, 2.5f, 10.0f};
            DecodeContext ctx = buildAlpCtxF32(expE, expF, encoded);

            // When
            FloatArray result = (FloatArray) DECODER.decode(ctx);

            // Then
            for (int i = 0; i < expected.length; i++) {
                assertThat(result.getFloat(i)).as("index %d", i).isCloseTo(expected[i], within(1e-6f));
            }
        }
    }

    @Nested
    class Encode {

        @Test
        void encode_f32_roundTrip_noPatches() {
            // Given
            float[] values = {1.0f, 2.5f, 3.75f, 10.0f, 0.1f};
            EncodeResult encoded = ENCODER.encode(DTypes.F32, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F32, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            FloatArray arr = (FloatArray) result;
            for (int i = 0; i < values.length; i++) {
                assertThat(arr.getFloat(i)).as("index %d", i).isCloseTo(values[i], within(1e-6f));
            }
        }

        @Test
        void encode_f32_roundTrip_withPatches() {
            // Given
            float[] values = {1.0f, Float.NaN, 2.5f, Float.POSITIVE_INFINITY, 3.0f};
            EncodeResult encoded = ENCODER.encode(DTypes.F32, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F32, REGISTRY);

            // When
            FloatArray result = (FloatArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getFloat(0)).isCloseTo(1.0f, within(1e-6f));
            assertThat(result.getFloat(1)).isNaN();
            assertThat(result.getFloat(2)).isCloseTo(2.5f, within(1e-6f));
            assertThat(result.getFloat(3)).isInfinite();
            assertThat(result.getFloat(4)).isCloseTo(3.0f, within(1e-6f));
        }

        @Test
        void encode_f64_roundTrip_noPatches() {
            // Given
            double[] values = {1.23, 4.56, 7.89, 0.001, 100.0};
            EncodeResult encoded = ENCODER.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F64, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            DoubleArray arr = (DoubleArray) result;
            for (int i = 0; i < values.length; i++) {
                assertThat(arr.getDouble(i)).as("index %d", i).isCloseTo(values[i], within(1e-9));
            }
        }

        @Test
        void encode_f64_roundTrip_negativeZeroPreservesSignBit() {
            // Given — the round-trip validity check used to compare candidate * df * de to the
            // original value with ==, and 0.0 == -0.0 in Java: a -0.0 input was accepted as
            // losslessly ALP-encodable, then reconstructed as +0.0 on decode, silently losing the
            // sign bit. Found running ParquetImporter's own output against the Parquet oracle for
            // two real Raincloud slugs (us-accidents, world-energy-consumption); this is otherwise
            // invisible in decimal string comparisons and even under isCloseTo(), which is why the
            // other round-trip tests in this class don't catch it.
            double[] values = {1.23, -0.0, 4.56, 0.0, 7.89};
            EncodeResult encoded = ENCODER.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F64, REGISTRY);

            // When
            DoubleArray result = (DoubleArray) DECODER.decode(ctx);

            // Then — bit-exact, not just numerically close: 0.0 == -0.0 would hide the bug
            for (int i = 0; i < values.length; i++) {
                assertThat(Double.doubleToRawLongBits(result.getDouble(i)))
                        .as("index %d", i)
                        .isEqualTo(Double.doubleToRawLongBits(values[i]));
            }
        }

        @Test
        void encode_f32_roundTrip_negativeZeroPreservesSignBit() {
            // Given — see encode_f64_roundTrip_negativeZeroPreservesSignBit; same bug, F32 path.
            float[] values = {1.23f, -0.0f, 4.56f, 0.0f, 7.89f};
            EncodeResult encoded = ENCODER.encode(DTypes.F32, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F32, REGISTRY);

            // When
            FloatArray result = (FloatArray) DECODER.decode(ctx);

            // Then
            for (int i = 0; i < values.length; i++) {
                assertThat(Float.floatToRawIntBits(result.getFloat(i)))
                        .as("index %d", i)
                        .isEqualTo(Float.floatToRawIntBits(values[i]));
            }
        }

        @Test
        void encode_f64_metadata_expE_isNonZero() throws Exception {
            // Given
            double[] values = {1.23, 4.56, 7.89};

            // When
            EncodeResult result = ENCODER.encode(DTypes.F64, values, EncodeTestHelper.testCtx());

            // Then
            MemorySegment metaSeg = result.rootNode().metadata();
            ProtoALPMetadata meta = ProtoALPMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(meta.exp_e()).isGreaterThan(0);
        }

        @Test
        void encode_f64_empty_doesNotThrow() {
            // Given — an all-zero-bit-pattern chunk (e.g. an all-null column) reaching this
            // encoder via SparseEncodingEncoder's cascade can strip every value, leaving n=0
            // (regression: division by zero in findExponentsF64's sample stride)
            double[] values = {};

            // When
            EncodeResult result = ENCODER.encode(DTypes.F64, values, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.statsMin()).isNull();
            assertThat(result.statsMax()).isNull();
        }

        @Test
        void encode_f32_empty_doesNotThrow() {
            // Given
            float[] values = {};

            // When
            EncodeResult result = ENCODER.encode(DTypes.F32, values, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.statsMin()).isNull();
            assertThat(result.statsMax()).isNull();
        }

        @Test
        void encodeCascade_f64_empty_doesNotThrow() {
            // Given — the actual crash path: CascadingCompressor calling encodeCascade directly
            // with a zero-length child array
            double[] values = {};

            // When
            CascadeStep result = ENCODER.encodeCascade(DTypes.F64, values, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.applicable()).isTrue();
            assertThat(result.statsMin()).isNull();
            assertThat(result.statsMax()).isNull();
        }
    }

    /// Property-based round-trip. ALP is a **lossless** codec: every value either fits the
    /// exponent model exactly or is stored verbatim as a patch. So `decode(encode(x))` must
    /// equal `x` **bit-for-bit** (raw bits — NaN payloads, subnormals, extremes all checked) —
    /// a stronger guarantee than the `isCloseTo` example tests above. Seeded for reproducibility.
    ///
    /// One documented exception: signed zero. ALP's round-trip check treats `-0.0 == 0.0`, so
    /// `-0.0` is not patched and decodes to `+0.0`. This is value-lossless but not bit-lossless,
    /// and matches the Rust reference (forcing a patch would diverge from it). The assertion
    /// canonicalizes ±0 to capture exactly that — everything else must be bit-identical.
    @Nested
    class PropertyRoundTrip {

        @ParameterizedTest
        @MethodSource("f64Cases")
        void f64_losslessBitExact(double[] values) {
            // When
            EncodeResult enc = ENCODER.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(enc, values.length, DTypes.F64, REGISTRY);
            DoubleArray result = (DoubleArray) DECODER.decode(ctx);

            // Then — bit-exact for every element (±0 canonicalized; see class doc)
            for (int i = 0; i < values.length; i++) {
                assertThat(canon(result.getDouble(i)))
                        .as("index %d value %s", i, values[i])
                        .isEqualTo(canon(values[i]));
            }
        }

        @ParameterizedTest
        @MethodSource("f32Cases")
        void f32_losslessBitExact(float[] values) {
            // When
            EncodeResult enc = ENCODER.encode(DTypes.F32, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(enc, values.length, DTypes.F32, REGISTRY);
            FloatArray result = (FloatArray) DECODER.decode(ctx);

            // Then — bit-exact (±0 canonicalized; see class doc)
            for (int i = 0; i < values.length; i++) {
                assertThat(canon(result.getFloat(i)))
                        .as("index %d value %s", i, values[i])
                        .isEqualTo(canon(values[i]));
            }
        }

        private static long canon(double d) {
            return d == 0.0 ? 0L : Double.doubleToRawLongBits(d); // map +0.0 and -0.0 to one key
        }

        private static int canon(float f) {
            return f == 0.0f ? 0 : Float.floatToRawIntBits(f);
        }

        static Stream<double[]> f64Cases() {
            Random rng = new Random(0xA1F64L);
            Stream.Builder<double[]> b = Stream.builder();
            // Curated edges: ±0, subnormals, extremes, non-finite — the classic float corner cases.
            b.add(new double[]{0.0, -0.0, 1.0, -1.0, 0.5, -0.25, 100.0, 3.14159,
                    Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_NORMAL, 1e-300, 1e300,
                    Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY});
            for (int n = 0; n < 50; n++) {
                int len = 1 + rng.nextInt(80);
                double[] a = new double[len];
                for (int i = 0; i < len; i++) {
                    a[i] = switch (rng.nextInt(3)) {
                        // ALP-friendly decimal (clean exponent path)
                        case 0 -> (rng.nextInt(2_000_000) - 1_000_000) / Math.pow(10, rng.nextInt(7));
                        // fully random bits (mostly the patch path)
                        case 1 -> Double.longBitsToDouble(rng.nextLong());
                        default -> rng.nextGaussian() * Math.pow(10, rng.nextInt(20) - 10);
                    };
                }
                b.add(a);
            }
            return b.build();
        }

        static Stream<float[]> f32Cases() {
            Random rng = new Random(0xA1F32L);
            Stream.Builder<float[]> b = Stream.builder();
            b.add(new float[]{0.0f, -0.0f, 1.0f, -1.0f, 0.5f, -0.25f, 100.0f, 3.14159f,
                    Float.MIN_VALUE, Float.MAX_VALUE, Float.MIN_NORMAL, 1e-30f, 1e30f,
                    Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY});
            for (int n = 0; n < 50; n++) {
                int len = 1 + rng.nextInt(80);
                float[] a = new float[len];
                for (int i = 0; i < len; i++) {
                    a[i] = switch (rng.nextInt(3)) {
                        case 0 -> (rng.nextInt(2_000_000) - 1_000_000) / (float) Math.pow(10, rng.nextInt(5));
                        case 1 -> Float.intBitsToFloat(rng.nextInt());
                        default -> (float) (rng.nextGaussian() * Math.pow(10, rng.nextInt(12) - 6));
                    };
                }
                b.add(a);
            }
            return b.build();
        }
    }
}
