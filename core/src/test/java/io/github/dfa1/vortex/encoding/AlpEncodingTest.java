package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AlpEncodingTest {


    @Nested
    class Decode {

        private static DecodeContext buildAlpCtxF64(
                int expE, int expF,
                long[] encodedVals,
                long[] patchIndices,
                double[] patchValues
        ) {
            EncodingProtos.ALPMetadata.Builder metaBuilder = EncodingProtos.ALPMetadata.newBuilder()
                                                                     .setExpE(expE).setExpF(expF);

            if (patchIndices != null) {
                EncodingProtos.PatchesMetadata pm = EncodingProtos.PatchesMetadata.newBuilder()
                                                            .setLen(patchIndices.length)
                                                            .setOffset(0)
                                                            .setIndicesPtype(io.github.dfa1.vortex.proto.DTypeProtos.PType.U32)
                                                            .build();
                metaBuilder.setPatches(pm);
            }

            byte[] metaBytes = metaBuilder.build().toByteArray();

            byte[] encBuf = new byte[encodedVals.length * 8];
            ByteBuffer bb = ByteBuffer.wrap(encBuf).order(ByteOrder.LITTLE_ENDIAN);
            for (long v : encodedVals) {
                bb.putLong(v);
            }

            ArrayNode encNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{0}, ArrayStats.empty());

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

                ArrayNode idxNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                        new ArrayNode[0], new int[]{1}, ArrayStats.empty());
                ArrayNode valNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                        new ArrayNode[0], new int[]{2}, ArrayStats.empty());

                children = new ArrayNode[]{encNode, idxNode, valNode};
                segments = new MemorySegment[]{
                        MemorySegment.ofArray(encBuf),
                        MemorySegment.ofArray(idxBuf),
                        MemorySegment.ofArray(valBuf)
                };
            } else {
                children = new ArrayNode[]{encNode};
                segments = new MemorySegment[]{MemorySegment.ofArray(encBuf)};
            }

            ArrayNode alpNode = ArrayNode.of(EncodingId.VORTEX_ALP,
                    ByteBuffer.wrap(metaBytes), children, new int[0], ArrayStats.empty());

            EncodingRegistry registry = TestRegistry.of(new AlpEncoding(), new PrimitiveEncoding());

            return new DecodeContext(alpNode, DTypes.F64, encodedVals.length, segments, registry, java.lang.foreign.Arena.global());
        }

        private static DecodeContext buildAlpCtxF32(
                int expE, int expF,
                int[] encodedVals
        ) {
            EncodingProtos.ALPMetadata.Builder metaBuilder = EncodingProtos.ALPMetadata.newBuilder()
                                                                     .setExpE(expE).setExpF(expF);

            byte[] metaBytes = metaBuilder.build().toByteArray();

            byte[] encBuf = new byte[encodedVals.length * 4];
            ByteBuffer bb = ByteBuffer.wrap(encBuf).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : encodedVals) {
                bb.putInt(v);
            }

            ArrayNode encNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{0}, ArrayStats.empty());

            ArrayNode alpNode = ArrayNode.of(EncodingId.VORTEX_ALP,
                    ByteBuffer.wrap(metaBytes), new ArrayNode[]{encNode}, new int[0], ArrayStats.empty());

            MemorySegment[] segments = {MemorySegment.ofArray(encBuf)};

            EncodingRegistry registry = TestRegistry.of(new AlpEncoding(), new PrimitiveEncoding());

            return new DecodeContext(alpNode, DTypes.F32, encodedVals.length, segments, registry, java.lang.foreign.Arena.global());
        }

        @Test
        void decode_f64_noPatches() {
            // Given — encode 1.23 with exp_e=2, exp_f=1: encoded = round(1.23 * 100 / 10) = 12
            // decode: 12 * 10^1 * 10^-2 = 12 * 10 * 0.01 = 1.2
            // Use exp_e=0, exp_f=2: encoded = round(1.23 * 1 / 100) ... let's use known values
            // encode: value * F10[e] * IF10[f] then round; decode: encoded * F10[f] * IF10[e]
            // Use e=2, f=0: encoded = round(1.23 * 100 * 1.0) = 123; decode = 123 * 1.0 * 0.01 = 1.23
            int expE = 2, expF = 0;
            long[] encoded = {123L, 456L, 789L};
            double[] expected = {1.23, 4.56, 7.89};

            DecodeContext ctx = buildAlpCtxF64(expE, expF, encoded, null, null);
            AlpEncoding sut = new AlpEncoding();

            // When
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(encoded.length);
            var layout = PTypeIO.LE_DOUBLE;
            for (int i = 0; i < expected.length; i++) {
                assertThat(result.buffer(0).get(layout, (long) i * 8))
                        .as("index %d", i).isCloseTo(expected[i], within(1e-9));
            }
        }

        @Test
        void decode_f64_withPatches() {
            // Given — 5 values, encoded = [100, 0, 200, 0, 300] with e=2,f=0 → [1.0, 0.0, 2.0, 0.0, 3.0]
            // patches at [1, 3] with real values [Double.NaN, Double.POSITIVE_INFINITY]
            int expE = 2, expF = 0;
            long[] encoded = {100L, 0L, 200L, 0L, 300L};
            long[] patchIndices = {1L, 3L};
            double[] patchValues = {Double.NaN, Double.POSITIVE_INFINITY};

            DecodeContext ctx = buildAlpCtxF64(expE, expF, encoded, patchIndices, patchValues);
            AlpEncoding sut = new AlpEncoding();

            // When
            Array result = sut.decode(ctx);

            // Then
            var layout = PTypeIO.LE_DOUBLE;
            assertThat(result.buffer(0).get(layout, 0L)).isCloseTo(1.0, within(1e-9));
            assertThat(result.buffer(0).get(layout, 8L)).isNaN();
            assertThat(result.buffer(0).get(layout, 16L)).isCloseTo(2.0, within(1e-9));
            assertThat(result.buffer(0).get(layout, 24L)).isInfinite();
            assertThat(result.buffer(0).get(layout, 32L)).isCloseTo(3.0, within(1e-9));
        }

        @ParameterizedTest
        @CsvSource({"0,0", "1,0", "2,1", "3,2", "4,3"})
        void decode_f64_exponentCombinations(int expE, int expF) {
            // Given — encode 42 with given exponents, then verify round-trip
            // encode: encoded = round(42.0 * F10[e] * IF10[f])
            double value = 42.0;
            double[] f10 = {1e0, 1e1, 1e2, 1e3, 1e4};
            double[] if10 = {1e-0, 1e-1, 1e-2, 1e-3, 1e-4};
            long encVal = Math.round(value * f10[expE] * if10[expF]);
            long[] encoded = {encVal};

            DecodeContext ctx = buildAlpCtxF64(expE, expF, encoded, null, null);
            AlpEncoding sut = new AlpEncoding();

            // When
            Array result = sut.decode(ctx);

            // Then
            var layout = PTypeIO.LE_DOUBLE;
            double decoded = result.buffer(0).get(layout, 0L);
            assertThat(decoded).isCloseTo(value, within(1e-6));
        }

        @Test
        void decode_f32_noPatches() {
            // Given — e=1, f=0: decode = encoded * 1.0 * 0.1
            int expE = 1, expF = 0;
            int[] encoded = {10, 25, 100};
            float[] expected = {1.0f, 2.5f, 10.0f};

            DecodeContext ctx = buildAlpCtxF32(expE, expF, encoded);
            AlpEncoding sut = new AlpEncoding();

            // When
            Array result = sut.decode(ctx);

            // Then
            var layout = PTypeIO.LE_FLOAT;
            for (int i = 0; i < expected.length; i++) {
                assertThat(result.buffer(0).get(layout, (long) i * 4))
                        .as("index %d", i).isCloseTo(expected[i], within(1e-6f));
            }
        }
    }

    @Nested
    class Encode {

        @Test
        void encode_f32_roundTrip_noPatches() {
            // Given
            float[] values = {1.0f, 2.5f, 3.75f, 10.0f, 0.1f};
            AlpEncoding sut = new AlpEncoding();

            EncodingRegistry registry = TestRegistry.withPrimitive(sut);

            // When
            EncodeResult encoded = sut.encode(DTypes.F32, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F32, registry);
            Array result = sut.decode(ctx);

            // Then
            var layout = PTypeIO.LE_FLOAT;
            for (int i = 0; i < values.length; i++) {
                assertThat(result.buffer(0).get(layout, (long) i * 4))
                        .as("index %d", i).isCloseTo(values[i], within(1e-6f));
            }
        }

        @Test
        void encode_f32_roundTrip_withPatches() {
            // Given — Float.NaN and Float.POSITIVE_INFINITY can't be ALP-encoded; must become patches
            float[] values = {1.0f, Float.NaN, 2.5f, Float.POSITIVE_INFINITY, 3.0f};
            AlpEncoding sut = new AlpEncoding();

            EncodingRegistry registry = TestRegistry.withPrimitive(sut);

            // When
            EncodeResult encoded = sut.encode(DTypes.F32, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F32, registry);
            Array result = sut.decode(ctx);

            // Then
            var layout = PTypeIO.LE_FLOAT;
            assertThat(result.buffer(0).get(layout, 0L)).isCloseTo(1.0f, within(1e-6f));
            assertThat(result.buffer(0).get(layout, 4L)).isNaN();
            assertThat(result.buffer(0).get(layout, 8L)).isCloseTo(2.5f, within(1e-6f));
            assertThat(result.buffer(0).get(layout, 12L)).isInfinite();
            assertThat(result.buffer(0).get(layout, 16L)).isCloseTo(3.0f, within(1e-6f));
        }

        @Test
        void encode_f64_roundTrip_noPatches() {
            // Given
            double[] values = {1.23, 4.56, 7.89, 0.001, 100.0};
            AlpEncoding sut = new AlpEncoding();

            EncodingRegistry registry = TestRegistry.withPrimitive(sut);

            // When
            EncodeResult encoded = sut.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, values.length, DTypes.F64, registry);
            Array result = sut.decode(ctx);

            // Then
            var layout = PTypeIO.LE_DOUBLE;
            for (int i = 0; i < values.length; i++) {
                assertThat(result.buffer(0).get(layout, (long) i * 8))
                        .as("index %d", i).isCloseTo(values[i], within(1e-9));
            }
        }

        @Test
        void encode_f64_metadata_expE_isNonZero() throws Exception {
            // Given — 2-decimal values force ALP to pick exp_e=2 (×100); if tag drifts, exp_e reads as 0
            double[] values = {1.23, 4.56, 7.89};
            AlpEncoding sut = new AlpEncoding();

            // When
            EncodeResult result = sut.encode(DTypes.F64, values, EncodeTestHelper.testCtx());
            EncodingProtos.ALPMetadata meta =
                    EncodingProtos.ALPMetadata.parseFrom(result.rootNode().metadata().duplicate());

            // Then
            assertThat(meta.getExpE()).isGreaterThan(0);
        }
    }
}
