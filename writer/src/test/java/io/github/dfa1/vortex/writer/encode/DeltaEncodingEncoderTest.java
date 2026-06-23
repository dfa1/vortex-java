package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.DeltaMetadata;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.reader.decode.DeltaEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
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
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_INT, (long) i * 4)).as("index %d", i).isEqualTo(data[i]);
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
            assertThat(result.materialize(Arena.ofAuto()).get(PTypeIO.LE_LONG, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
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

    // Property test: seeded-random arrays across every accepted integer ptype and a range of sizes.
    // The hand-picked cases above all stay under one FastLanes chunk (1024); the 1024/1025/3000 sizes
    // here exercise the multi-chunk loop, the cross-chunk transpose, and the offset-slice tail — the
    // bulk of the encode/decode logic that small arrays never reach.
    @ParameterizedTest(name = "{0}")
    @MethodSource("randomIntegerArrays")
    void encodeDecode_randomAcrossPtypesAndSizes_isLossless(String name, DType dtype, Object data, int n) {
        // Given
        EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
        DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, n, dtype, REGISTRY);

        // When
        Array result = DECODER.decode(ctx);

        // Then — round-trip reproduces every element's raw bytes exactly
        assertThat(result.length()).isEqualTo(n);
        MemorySegment seg = result.materialize(Arena.ofAuto());
        PType ptype = ((DType.Primitive) dtype).ptype();
        for (int i = 0; i < n; i++) {
            long off = (long) i * ptype.byteSize();
            switch (ptype) {
                case I8, U8 -> assertThat(seg.get(ValueLayout.JAVA_BYTE, off)).as("idx %d", i).isEqualTo(((byte[]) data)[i]);
                case I16, U16 -> assertThat(seg.get(PTypeIO.LE_SHORT, off)).as("idx %d", i).isEqualTo(((short[]) data)[i]);
                case I32, U32 -> assertThat(seg.get(PTypeIO.LE_INT, off)).as("idx %d", i).isEqualTo(((int[]) data)[i]);
                case I64, U64 -> assertThat(seg.get(PTypeIO.LE_LONG, off)).as("idx %d", i).isEqualTo(((long[]) data)[i]);
                default -> throw new AssertionError(ptype);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"I8", "I16", "I32", "I64", "U8", "U16", "U32", "U64"})
    void accepts_everyIntegerPtype_isTrue(String ptype) {
        // Given / When / Then
        assertThat(ENCODER.accepts(new DType.Primitive(PType.valueOf(ptype), false))).isTrue();
    }

    @Test
    void accepts_nonIntegerOrNonPrimitive_isFalse() {
        // Given / When / Then — floats and non-primitive dtypes are rejected
        assertThat(ENCODER.accepts(DTypes.F64)).isFalse();
        assertThat(ENCODER.accepts(DTypes.UTF8)).isFalse();
    }

    @Test
    void encode_signedI64_statsCarryMinAndMax() throws Exception {
        // Given — unordered; min/max are interior so a broken scan (negated compare) picks a wrong value
        long[] data = {30L, -10L, 50L, 20L, 40L};

        // When
        EncodeResult result = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());

        // Then — signed stats use the int64 scalar field, min/max by signed ordering
        assertThat(result.hasStats()).isTrue();
        assertThat(scalar(result.statsMin()).int64_value()).isEqualTo(-10L);
        assertThat(scalar(result.statsMax()).int64_value()).isEqualTo(50L);
    }

    @Test
    void encode_unsignedU64_statsUseUnsignedOrderingAndField() throws Exception {
        // Given — -1L is the max value under unsigned ordering but the min under signed ordering, so this
        // pins both the unsigned compare (lines 57/60) and the unsigned stats field (isUnsigned/statsBytes)
        long[] data = {1L, -1L, 5L};

        // When
        EncodeResult result = ENCODER.encode(DTypes.U64, data, EncodeTestHelper.testCtx());

        // Then
        assertThat(scalar(result.statsMin()).uint64_value()).isEqualTo(1L);
        assertThat(scalar(result.statsMax()).uint64_value()).isEqualTo(-1L);
    }

    @Test
    void encode_empty_hasNoStats() {
        // Given / When — the n>0 guard must suppress stats for an empty array
        EncodeResult result = ENCODER.encode(DTypes.I64, new long[0], EncodeTestHelper.testCtx());

        // Then
        assertThat(result.statsMin()).isNull();
        assertThat(result.statsMax()).isNull();
        assertThat(result.hasStats()).isFalse();
    }

    private static ScalarValue scalar(byte[] bytes) throws java.io.IOException {
        MemorySegment seg = MemorySegment.ofArray(bytes);
        return ScalarValue.decode(seg, 0, seg.byteSize());
    }

    private static Stream<Arguments> randomIntegerArrays() {
        Random rng = new Random(0xD317A1L);
        // 0 → empty path; 1/5 → sub-chunk; 1024 → exactly one chunk; 1025/3000 → multi-chunk + tail slice.
        int[] sizes = {0, 1, 5, 1024, 1025, 3000};
        DType[] dtypes = {DTypes.I8, DTypes.I16, DTypes.I32, DTypes.I64, DTypes.U8, DTypes.U16, DTypes.U32, DTypes.U64};
        List<Arguments> out = new ArrayList<>();
        for (DType dtype : dtypes) {
            PType ptype = ((DType.Primitive) dtype).ptype();
            for (int n : sizes) {
                out.add(Arguments.of(ptype + "/" + n, dtype, randomArray(ptype, n, rng), n));
            }
        }
        return out.stream();
    }

    private static Object randomArray(PType ptype, int n, Random rng) {
        return switch (ptype) {
            case I8, U8 -> {
                byte[] a = new byte[n];
                rng.nextBytes(a);
                yield a;
            }
            case I16, U16 -> {
                short[] a = new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (short) rng.nextInt();
                }
                yield a;
            }
            case I32, U32 -> {
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = rng.nextInt();
                }
                yield a;
            }
            case I64, U64 -> {
                long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = rng.nextLong();
                }
                yield a;
            }
            default -> throw new AssertionError(ptype);
        };
    }
}
