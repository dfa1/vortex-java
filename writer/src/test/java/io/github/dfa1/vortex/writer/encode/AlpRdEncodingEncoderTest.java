package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoALPRDMetadata;
import io.github.dfa1.vortex.reader.decode.AlpRdEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.BitpackedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AlpRdEncodingEncoderTest {

    @Test
    void encode_f64_roundTrip() {
        // Given
        double[] values = {0.1, 0.2, 0.3, 0.4, 0.5};
        var encoder = new AlpRdEncodingEncoder();
        var decoder = new AlpRdEncodingDecoder();
        ReadRegistry registry = TestRegistry.ofDecoders(decoder, new BitpackedEncodingDecoder(), new PrimitiveEncodingDecoder());

        // When
        EncodeResult encoded = encoder.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F64, registry);
        var result = decoder.decode(ctx);

        // Then
        for (int i = 0; i < values.length; i++) {
            assertThat(((io.github.dfa1.vortex.reader.array.DoubleArray) result).getDouble(i))
                    .as("index %d", i).isCloseTo(values[i], within(1e-9));
        }
    }

    @Test
    void encode_f32_roundTrip() {
        // Given — exercises the F32 path (encodeF32 + Dictionary32 selection),
        // distinct from the F64 path which uses Dictionary64
        float[] values = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f};
        var encoder = new AlpRdEncodingEncoder();
        var decoder = new AlpRdEncodingDecoder();
        ReadRegistry registry = TestRegistry.ofDecoders(decoder, new BitpackedEncodingDecoder(), new PrimitiveEncodingDecoder());

        // When
        EncodeResult encoded = encoder.encode(DTypes.F32, values, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F32, registry);
        var result = decoder.decode(ctx);

        // Then
        for (int i = 0; i < values.length; i++) {
            assertThat(((io.github.dfa1.vortex.reader.array.FloatArray) result).getFloat(i))
                    .as("index %d", i).isCloseTo(values[i], within(1e-6f));
        }
    }

    @Test
    void encode_f64_metadata_rightBitWidth_isNonZero() throws Exception {
        // Given — ALPRD splits F64 into left+right parts; right_bit_width>0 means real encoding happened
        // if tag drifts, right_bit_width reads as 0 (proto3 default) and right parts are all zero
        double[] values = {0.1, 0.2, 0.3, 0.4, 0.5};
        var sut = new AlpRdEncodingEncoder();

        // When
        EncodeResult result = sut.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
        var metaSeg = result.rootNode().metadata();
        ProtoALPRDMetadata meta = ProtoALPRDMetadata.decode(metaSeg, 0, metaSeg.byteSize());

        // Then
        assertThat(meta.right_bit_width()).isGreaterThan(0);
    }

    // Property test: ALPRD is a lossless raw-bit split (dictionary left parts + bit-packed right parts,
    // exceptions stored verbatim), so the round-trip must be *bit-exact* for arbitrary finite values —
    // including -0.0 and exception-heavy random data where most left parts miss the 8-entry dictionary.
    // Sizes past 512 (the sampling window) and past one bit-pack chunk exercise the exception + multi-
    // chunk paths the 5-element happy-path tests never reach.
    @ParameterizedTest(name = "f64/{0}")
    @MethodSource("sizes")
    void encodeDecode_randomF64_isBitExact(int n) {
        // Given
        double[] values = randomDoubles(n, new Random(0xA1B2C3D4L + n));
        var decoder = new AlpRdEncodingDecoder();
        ReadRegistry registry = TestRegistry.ofDecoders(decoder, new BitpackedEncodingDecoder(), new PrimitiveEncodingDecoder());

        // When
        EncodeResult encoded = new AlpRdEncodingEncoder().encode(DTypes.F64, values, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, DTypes.F64, registry);
        DoubleArray result = (DoubleArray) decoder.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(n);
        for (int i = 0; i < n; i++) {
            assertThat(Double.doubleToRawLongBits(result.getDouble(i)))
                    .as("idx %d", i).isEqualTo(Double.doubleToRawLongBits(values[i]));
        }
    }

    @ParameterizedTest(name = "f32/{0}")
    @MethodSource("sizes")
    void encodeDecode_randomF32_isBitExact(int n) {
        // Given
        float[] values = randomFloats(n, new Random(0xE5F60718L + n));
        var decoder = new AlpRdEncodingDecoder();
        ReadRegistry registry = TestRegistry.ofDecoders(decoder, new BitpackedEncodingDecoder(), new PrimitiveEncodingDecoder());

        // When
        EncodeResult encoded = new AlpRdEncodingEncoder().encode(DTypes.F32, values, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, DTypes.F32, registry);
        FloatArray result = (FloatArray) decoder.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(n);
        for (int i = 0; i < n; i++) {
            assertThat(Float.floatToRawIntBits(result.getFloat(i)))
                    .as("idx %d", i).isEqualTo(Float.floatToRawIntBits(values[i]));
        }
    }

    @Test
    void accepts_floatPtypesOnly() {
        // Given / When / Then — only F32/F64 are encodable; integers and non-primitives are rejected
        var encoder = new AlpRdEncodingEncoder();
        assertThat(encoder.accepts(DTypes.F32)).isTrue();
        assertThat(encoder.accepts(DTypes.F64)).isTrue();
        assertThat(encoder.accepts(DTypes.I64)).isFalse();
        assertThat(encoder.accepts(DTypes.UTF8)).isFalse();
    }

    private static Stream<Arguments> sizes() {
        // 0 → empty path; 1/5 → sub-sample; 1024/1025/3000 → past the 512 sample window + multi-chunk.
        return Stream.of(0, 1, 5, 1024, 1025, 3000).map(Arguments::of);
    }

    private static double[] randomDoubles(int n, Random rng) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            double d;
            do {
                d = Double.longBitsToDouble(rng.nextLong());
            } while (!Double.isFinite(d));
            a[i] = d;
        }
        return a;
    }

    private static float[] randomFloats(int n, Random rng) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            float f;
            do {
                f = Float.intBitsToFloat(rng.nextInt());
            } while (!Float.isFinite(f));
            a[i] = f;
        }
        return a;
    }
}
