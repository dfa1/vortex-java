package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.ALPMetadata;
import io.github.dfa1.vortex.proto.PatchesMetadata;
import io.github.dfa1.vortex.reader.decode.AlpEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
            PatchesMetadata pm = patchIndices != null
                    ? new PatchesMetadata((long) patchIndices.length, 0L,
                            io.github.dfa1.vortex.proto.PType.U32, null, null, null)
                    : null;
            byte[] metaBytes = new ALPMetadata(expE, expF, pm).encode();

            byte[] encBuf = new byte[encodedVals.length * 8];
            ByteBuffer bb = ByteBuffer.wrap(encBuf).order(ByteOrder.LITTLE_ENDIAN);
            for (long v : encodedVals) {
                bb.putLong(v);
            }

            ArrayNode encNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
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
                ArrayNode idxNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
                ArrayNode valNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{2});
                children = new ArrayNode[]{encNode, idxNode, valNode};
                segments = new MemorySegment[]{
                        MemorySegment.ofArray(encBuf), MemorySegment.ofArray(idxBuf), MemorySegment.ofArray(valBuf)};
            } else {
                children = new ArrayNode[]{encNode};
                segments = new MemorySegment[]{MemorySegment.ofArray(encBuf)};
            }

            ArrayNode alpNode = ArrayNode.of(EncodingId.VORTEX_ALP,
                    ByteBuffer.wrap(metaBytes), children, new int[0]);

            return new DecodeContext(alpNode, DTypes.F64, encodedVals.length, segments, REGISTRY, java.lang.foreign.Arena.global());
        }

        private static DecodeContext buildAlpCtxF32(int expE, int expF, int[] encodedVals) {
            byte[] metaBytes = new ALPMetadata(expE, expF, null).encode();
            byte[] encBuf = new byte[encodedVals.length * 4];
            ByteBuffer bb = ByteBuffer.wrap(encBuf).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : encodedVals) {
                bb.putInt(v);
            }
            ArrayNode encNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
            ArrayNode alpNode = ArrayNode.of(EncodingId.VORTEX_ALP, ByteBuffer.wrap(metaBytes),
                    new ArrayNode[]{encNode}, new int[0]);
            MemorySegment[] segments = {MemorySegment.ofArray(encBuf)};
            return new DecodeContext(alpNode, DTypes.F32, encodedVals.length, segments, REGISTRY, java.lang.foreign.Arena.global());
        }

        @Test
        void decode_f64_noPatches() {
            int expE = 2, expF = 0;
            long[] encoded = {123L, 456L, 789L};
            double[] expected = {1.23, 4.56, 7.89};

            DecodeContext ctx = buildAlpCtxF64(expE, expF, encoded, null, null);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(encoded.length);
            for (int i = 0; i < expected.length; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, (long) i * 8))
                        .as("index %d", i).isCloseTo(expected[i], within(1e-9));
            }
        }

        @Test
        void decode_f64_withPatches() {
            int expE = 2, expF = 0;
            long[] encoded = {100L, 0L, 200L, 0L, 300L};
            long[] patchIndices = {1L, 3L};
            double[] patchValues = {Double.NaN, Double.POSITIVE_INFINITY};

            DecodeContext ctx = buildAlpCtxF64(expE, expF, encoded, patchIndices, patchValues);
            Array result = DECODER.decode(ctx);

            assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, 0L)).isCloseTo(1.0, within(1e-9));
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, 8L)).isNaN();
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, 16L)).isCloseTo(2.0, within(1e-9));
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, 24L)).isInfinite();
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, 32L)).isCloseTo(3.0, within(1e-9));
        }

        @ParameterizedTest
        @CsvSource({"0,0", "1,0", "2,1", "3,2", "4,3"})
        void decode_f64_exponentCombinations(int expE, int expF) {
            double value = 42.0;
            double[] f10 = {1e0, 1e1, 1e2, 1e3, 1e4};
            double[] if10 = {1e-0, 1e-1, 1e-2, 1e-3, 1e-4};
            long encVal = Math.round(value * f10[expE] * if10[expF]);
            long[] encoded = {encVal};

            DecodeContext ctx = buildAlpCtxF64(expE, expF, encoded, null, null);
            Array result = DECODER.decode(ctx);

            double decoded = ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, 0L);
            assertThat(decoded).isCloseTo(value, within(1e-6));
        }

        @Test
        void decode_f32_noPatches() {
            int expE = 1, expF = 0;
            int[] encoded = {10, 25, 100};
            float[] expected = {1.0f, 2.5f, 10.0f};

            DecodeContext ctx = buildAlpCtxF32(expE, expF, encoded);
            Array result = DECODER.decode(ctx);

            for (int i = 0; i < expected.length; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_FLOAT, (long) i * 4))
                        .as("index %d", i).isCloseTo(expected[i], within(1e-6f));
            }
        }
    }

    @Nested
    class Encode {

        @Test
        void encode_f32_roundTrip_noPatches() {
            float[] values = {1.0f, 2.5f, 3.75f, 10.0f, 0.1f};

            EncodeResult encoded = ENCODER.encode(DTypes.F32, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F32, REGISTRY);
            Array result = DECODER.decode(ctx);

            for (int i = 0; i < values.length; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_FLOAT, (long) i * 4))
                        .as("index %d", i).isCloseTo(values[i], within(1e-6f));
            }
        }

        @Test
        void encode_f32_roundTrip_withPatches() {
            float[] values = {1.0f, Float.NaN, 2.5f, Float.POSITIVE_INFINITY, 3.0f};

            EncodeResult encoded = ENCODER.encode(DTypes.F32, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F32, REGISTRY);
            Array result = DECODER.decode(ctx);

            assertThat(ArraySegments.of(result).get(PTypeIO.LE_FLOAT, 0L)).isCloseTo(1.0f, within(1e-6f));
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_FLOAT, 4L)).isNaN();
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_FLOAT, 8L)).isCloseTo(2.5f, within(1e-6f));
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_FLOAT, 12L)).isInfinite();
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_FLOAT, 16L)).isCloseTo(3.0f, within(1e-6f));
        }

        @Test
        void encode_f64_roundTrip_noPatches() {
            double[] values = {1.23, 4.56, 7.89, 0.001, 100.0};

            EncodeResult encoded = ENCODER.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F64, REGISTRY);
            Array result = DECODER.decode(ctx);

            for (int i = 0; i < values.length; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_DOUBLE, (long) i * 8))
                        .as("index %d", i).isCloseTo(values[i], within(1e-9));
            }
        }

        @Test
        void encode_f64_metadata_expE_isNonZero() throws Exception {
            double[] values = {1.23, 4.56, 7.89};

            EncodeResult result = ENCODER.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
            MemorySegment metaSeg = MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
            ALPMetadata meta = ALPMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(meta.exp_e()).isGreaterThan(0);
        }
    }
}
