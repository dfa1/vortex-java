package io.github.dfa1.vortex.writer.encode;


import io.github.dfa1.vortex.core.model.DType;

import io.github.dfa1.vortex.core.model.PType;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoBitPackedMetadata;
import io.github.dfa1.vortex.reader.decode.BitpackedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: encode then decode is lossless for unsigned integer types.
class BitpackedEncodingEncoderTest {

    private static final BitpackedEncodingEncoder ENCODER = new BitpackedEncodingEncoder();
    private static final BitpackedEncodingDecoder DECODER = new BitpackedEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER);
    // Patches decode through their primitive children.
    private static final ReadRegistry PATCHED_REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

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
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
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
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
        }
    }

    @Test
    void encode_i32_metadata_bitWidth_isNonZero() throws Exception {
        // Given — max value 5 needs 3 bits; if tag drifts, bit_width reads as 0 (proto3 default)
        int[] data = {1, 2, 3, 4, 5};

        EncodeResult result = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
        var metaSeg = result.rootNode().metadata();
        ProtoBitPackedMetadata meta = ProtoBitPackedMetadata.decode(metaSeg, 0, metaSeg.byteSize());

        assertThat(meta.bit_width()).isGreaterThan(0);
    }

    /// Property: lossless pack/unpack at **every** bit width. Each array forces a specific
    /// width by including its max value `2^W - 1`, fills the rest with random values masked to
    /// W bits, and checks the boundary widths (1, 7, 8, 31/32, 63/64) explicitly. The random
    /// arrays' values don't deterministically exercise every width, so this pins them all.
    @ParameterizedTest(name = "u32 width={0}")
    @MethodSource("u32Widths")
    void encodeDecode_u32_everyBitWidth(int width, int[] data) {
        EncodeResult encoded = ENCODER.encode(DTypes.U32, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.U32, REGISTRY);
        Array result = DECODER.decode(ctx);

        var seg = result.materialize(Arena.ofAuto());
        for (int i = 0; i < data.length; i++) {
            assertThat(seg.get(VortexFormat.LE_INT, (long) i * 4)).as("width %d index %d", width, i).isEqualTo(data[i]);
        }
    }

    @ParameterizedTest(name = "u64 width={0}")
    @MethodSource("u64Widths")
    void encodeDecode_u64_everyBitWidth(int width, long[] data) {
        EncodeResult encoded = ENCODER.encode(DTypes.U64, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.U64, REGISTRY);
        Array result = DECODER.decode(ctx);

        var seg = result.materialize(Arena.ofAuto());
        for (int i = 0; i < data.length; i++) {
            assertThat(seg.get(VortexFormat.LE_LONG, (long) i * 8)).as("width %d index %d", width, i).isEqualTo(data[i]);
        }
    }

    /// Every word width (the per-width store paths), across block boundaries — a full block, one
    /// row over (a partial last block), several blocks — with ~1% of values too wide for the
    /// chosen width, so the patch path runs on the histogram's patch count. Small arrays alone
    /// never reach a second block.
    @ParameterizedTest(name = "{0} n={1}")
    @MethodSource("multiBlockArrays")
    void encodeDecode_multiBlockWithPatches_isLossless(PType ptype, int n, long[] values) {
        // Given
        DType dtype = new DType.Primitive(ptype, false);
        Object data = carrier(ptype, values);

        // When
        EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
        Array result = DECODER.decode(DecodeTestHelper.toDecodeContext(encoded, n, dtype, PATCHED_REGISTRY));

        // Then
        var seg = result.materialize(Arena.ofAuto());
        long mask = ptype.bits() == 64 ? -1L : (1L << ptype.bits()) - 1L;
        for (int i = 0; i < n; i++) {
            assertThat(readWord(seg, i, ptype) & mask)
                    .as("%s index %d", ptype, i).isEqualTo(values[i] & mask);
        }
    }

    static Stream<Arguments> multiBlockArrays() {
        Random rng = new Random(0xB10C5L);
        return Stream.of(PType.U8, PType.U16, PType.U32, PType.U64).flatMap(ptype ->
                Stream.of(1024, 1025, 3_000).map(n -> {
                    int width = Math.max(1, ptype.bits() / 2 - 1);
                    long[] values = new long[n];
                    for (int i = 0; i < n; i++) {
                        values[i] = i % 97 == 0
                                ? rng.nextLong() >>> (64 - ptype.bits())   // patch: full type width
                                : rng.nextLong() >>> (64 - width);
                    }
                    return Arguments.of(ptype, n, values);
                }));
    }

    private static long readWord(java.lang.foreign.MemorySegment seg, int i, PType ptype) {
        return switch (ptype.bits()) {
            case 8 -> seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i);
            case 16 -> seg.get(VortexFormat.LE_SHORT, 2L * i);
            case 32 -> seg.get(VortexFormat.LE_INT, 4L * i);
            default -> seg.get(VortexFormat.LE_LONG, 8L * i);
        };
    }

    private static Object carrier(PType ptype, long[] values) {
        int n = values.length;
        return switch (ptype.bits()) {
            case 8 -> {
                byte[] out = new byte[n];
                for (int i = 0; i < n; i++) {
                    out[i] = (byte) values[i];
                }
                yield out;
            }
            case 16 -> {
                short[] out = new short[n];
                for (int i = 0; i < n; i++) {
                    out[i] = (short) values[i];
                }
                yield out;
            }
            case 32 -> {
                int[] out = new int[n];
                for (int i = 0; i < n; i++) {
                    out[i] = (int) values[i];
                }
                yield out;
            }
            default -> values;
        };
    }

    static Stream<Arguments> u32Widths() {
        Random rng = new Random(0xB17BACEDL);
        return Stream.iterate(1, w -> w <= 32, w -> w + 1).map(w -> {
            int mask = w == 32 ? -1 : (1 << w) - 1; // -1 == 0xFFFFFFFF for the full width
            int[] a = new int[40];
            a[0] = 0;
            a[1] = mask; // 2^w - 1 — forces the encoder to pick width w
            for (int i = 2; i < a.length; i++) {
                a[i] = rng.nextInt() & mask;
            }
            return Arguments.of(w, a);
        });
    }

    static Stream<Arguments> u64Widths() {
        Random rng = new Random(0xB17BACE2L);
        return Stream.iterate(1, w -> w <= 64, w -> w + 1).map(w -> {
            long mask = w == 64 ? -1L : (1L << w) - 1L;
            long[] a = new long[40];
            a[0] = 0L;
            a[1] = mask;
            for (int i = 2; i < a.length; i++) {
                a[i] = rng.nextLong() & mask;
            }
            return Arguments.of(w, a);
        });
    }
}
