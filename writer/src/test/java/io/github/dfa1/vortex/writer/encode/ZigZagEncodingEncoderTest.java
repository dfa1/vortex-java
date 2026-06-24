package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ZigZagEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ZigZagEncodingEncoderTest {

    private static final ZigZagEncodingEncoder ENCODER = new ZigZagEncodingEncoder();
    private static final ZigZagEncodingDecoder DECODER = new ZigZagEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    @Nested
    class Decode {

        static Stream<Arguments> i32Cases() {
            return Stream.of(
                    Arguments.of("zeros", new int[]{0, 0, 0}, new int[]{0, 0, 0}),
                    Arguments.of("mixed", new int[]{0, 1, 2, 3, 4}, new int[]{0, -1, 1, -2, 2}),
                    Arguments.of("large", new int[]{Integer.MAX_VALUE & ~1, (Integer.MAX_VALUE & ~1) | 1},
                            new int[]{Integer.MAX_VALUE / 2, Integer.MIN_VALUE / 2})
            );
        }

        private static DecodeContext buildI32Ctx(int[] encodedUnsigned) {
            ByteBuffer buf = ByteBuffer.allocate(encodedUnsigned.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : encodedUnsigned) {
                buf.putInt(v);
            }
            buf.flip();
            MemorySegment seg = MemorySegment.ofBuffer(buf);

            ArrayNode primitiveNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
            ArrayNode zigzagNode = ArrayNode.of(EncodingId.VORTEX_ZIGZAG, null, new ArrayNode[]{primitiveNode}, new int[0]);

            return new DecodeContext(zigzagNode, DTypes.I32, encodedUnsigned.length,
                    new MemorySegment[]{seg}, REGISTRY, Arena.ofAuto());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("i32Cases")
        void decode_i32_zigzagDecodesCorrectly(String name, int[] encoded, int[] expected) {
            // Given
            DecodeContext ctx = buildI32Ctx(encoded);

            // When
            var result = DECODER.decode(ctx);

            // Then
            assertThat(result).isInstanceOf(IntArray.class);
            assertThat(result.length()).isEqualTo(expected.length);
            IntArray arr = (IntArray) result;
            for (int i = 0; i < expected.length; i++) {
                assertThat(arr.getInt(i)).as("index %d", i).isEqualTo(expected[i]);
            }
        }

        @Test
        void decode_empty_returnsEmptyArray() {
            // Given
            DecodeContext ctx = buildI32Ctx(new int[]{});

            // When
            var result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isZero();
        }
    }

    @Test
    void encodingId_isVortexZigzag() {
        // Given / When / Then
        assertThat(ENCODER.encodingId()).isEqualTo(EncodingId.VORTEX_ZIGZAG);
    }

    @Test
    void accepts_signedIntegers_true() {
        // Given / When / Then
        assertThat(ENCODER.accepts(DTypes.I8)).isTrue();
        assertThat(ENCODER.accepts(DTypes.I16)).isTrue();
        assertThat(ENCODER.accepts(DTypes.I32)).isTrue();
        assertThat(ENCODER.accepts(DTypes.I64)).isTrue();
    }

    @Test
    void accepts_unsignedOrNonPrimitive_false() {
        // Given / When / Then
        assertThat(ENCODER.accepts(DTypes.U32)).isFalse();
        assertThat(ENCODER.accepts(DTypes.F64)).isFalse();
        assertThat(ENCODER.accepts(DType.UTF8)).isFalse();
    }

    @Nested
    class Encode {

        static Stream<byte[]> i8RoundtripArrays() {
            return Stream.of(
                    new byte[]{},
                    new byte[]{0},
                    new byte[]{-1, 1, -2, 2},
                    new byte[]{Byte.MIN_VALUE, Byte.MAX_VALUE, 0}
            );
        }

        static Stream<short[]> i16RoundtripArrays() {
            return Stream.of(
                    new short[]{},
                    new short[]{0},
                    new short[]{Short.MIN_VALUE, Short.MAX_VALUE, 0},
                    new short[]{-100, 100, -1000, 1000}
            );
        }

        @ParameterizedTest
        @MethodSource("i8RoundtripArrays")
        void encodeDecode_i8_isLossless(byte[] data) {
            // Given
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, data.length, DTypes.I8, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            ByteArray arr = (ByteArray) result;
            for (int i = 0; i < data.length; i++) {
                assertThat(arr.getByte(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @MethodSource("i16RoundtripArrays")
        void encodeDecode_i16_isLossless(short[] data) {
            // Given
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I16, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, data.length, DTypes.I16, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            ShortArray arr = (ShortArray) result;
            for (int i = 0; i < data.length; i++) {
                assertThat(arr.getShort(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        static Stream<int[]> i32RoundtripArrays() {
            return Stream.of(
                    new int[]{},
                    new int[]{0},
                    new int[]{-1, 1, -2, 2},
                    new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, 0},
                    new int[]{-100, 0, 100, -1000, 1000}
            );
        }

        static Stream<long[]> i64RoundtripArrays() {
            return Stream.of(
                    new long[]{},
                    new long[]{0L},
                    new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 0L},
                    new long[]{-1L, 1L, -2L, 2L}
            );
        }

        @ParameterizedTest
        @MethodSource("i32RoundtripArrays")
        void encodeDecode_i32_isLossless(int[] data) {
            // Given
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I32, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, data.length, DTypes.I32, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            IntArray arr = (IntArray) result;
            for (int i = 0; i < data.length; i++) {
                assertThat(arr.getInt(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @ParameterizedTest
        @MethodSource("i64RoundtripArrays")
        void encodeDecode_i64_isLossless(long[] data) {
            // Given
            EncodeResult resultEncoded = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(resultEncoded, data.length, DTypes.I64, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            LongArray arr = (LongArray) result;
            for (int i = 0; i < data.length; i++) {
                assertThat(arr.getLong(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }
    }
}
