package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.ProtoRunEndMetadata;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.RunEndEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RunEndEncodingEncoderTest {

    private static final RunEndEncodingEncoder ENCODER = new RunEndEncodingEncoder();
    private static final RunEndEncodingDecoder DECODER = new RunEndEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    @Nested
    class Decode {

        private static DecodeContext buildCtx(
                DType dtype, long rowCount,
                long[] ends, long[] values, PType endsPtype, long offset
        ) {
            byte[] metaBytes = new ProtoRunEndMetadata(
                    io.github.dfa1.vortex.proto.ProtoPType.fromValue(endsPtype.ordinal()), ends.length, offset).encode();

            byte[] endsBuf = toLEBytes(ends, endsPtype);
            byte[] valBuf = toLEBytes(values, PType.I64);

            ArrayNode endsNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{0});
            ArrayNode valsNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{1});
            ArrayNode reNode = ArrayNode.of(EncodingId.VORTEX_RUNEND,
                    MemorySegment.ofArray(metaBytes),
                    new ArrayNode[]{endsNode, valsNode},
                    new int[0]);

            MemorySegment[] segments = {
                    MemorySegment.ofArray(endsBuf),
                    MemorySegment.ofArray(valBuf)
            };

            return new DecodeContext(reNode, dtype, rowCount, segments, REGISTRY, java.lang.foreign.Arena.global());
        }

        private static byte[] toLEBytes(long[] values, PType ptype) {
            int elemBytes = ptype.byteSize();
            byte[] buf = new byte[values.length * elemBytes];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            for (long v : values) {
                switch (ptype) {
                    case U8, I8 -> bb.put((byte) v);
                    case U16, I16 -> bb.putShort((short) v);
                    case U32, I32 -> bb.putInt((int) v);
                    case U64, I64 -> bb.putLong(v);
                    default -> throw new UnsupportedOperationException(ptype.name());
                }
            }
            return buf;
        }

        @Test
        void decode_singleRun_fillsAllElements() {
            // Given
            long[] ends = {5L};
            long[] values = {42L};
            DecodeContext ctx = buildCtx(DTypes.I64, 5, ends, values, PType.U32, 0L);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(5L);
            for (int i = 0; i < 5; i++) {
                assertThat(((io.github.dfa1.vortex.reader.array.LongArray) result).getLong(i)).isEqualTo(42L);
            }
        }

        @Test
        void decode_multipleRuns_expandsCorrectly() {
            // Given
            long[] ends = {2L, 5L, 7L};
            long[] values = {10L, 20L, 30L};
            DecodeContext ctx = buildCtx(DTypes.I64, 7, ends, values, PType.U32, 0L);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            long[] expected = {10, 10, 20, 20, 20, 30, 30};
            for (int i = 0; i < expected.length; i++) {
                assertThat(((io.github.dfa1.vortex.reader.array.LongArray) result).getLong(i))
                        .as("index %d", i).isEqualTo(expected[i]);
            }
        }

        @Test
        void decode_withOffset_skipsLogicalElements() {
            // Given
            long[] ends = {3L, 6L};
            long[] values = {10L, 20L};
            DecodeContext ctx = buildCtx(DTypes.I64, 3, ends, values, PType.U32, 2L);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            long[] expected = {10L, 20L, 20L};
            for (int i = 0; i < expected.length; i++) {
                assertThat(((io.github.dfa1.vortex.reader.array.LongArray) result).getLong(i))
                        .as("index %d", i).isEqualTo(expected[i]);
            }
        }
    }

    @Nested
    class Encode {

        static Stream<long[]> i64Arrays() {
            return Stream.of(
                    new long[]{42L},
                    new long[]{1L, 1L, 2L, 2L, 3L},
                    new long[]{0L, 0L, 0L, 0L, 0L},
                    new long[]{10L, 20L, 10L, 20L},
                    new long[]{-1L, -1L, -1L, 0L, 0L, 1L}
            );
        }

        @ParameterizedTest
        @MethodSource("i64Arrays")
        void encodeDecode_i64_isLossless(long[] data) {
            // Given
            EncodeResult encoded = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I64, REGISTRY);

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            for (int i = 0; i < data.length; i++) {
                assertThat(((io.github.dfa1.vortex.reader.array.LongArray) result).getLong(i)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_i64_metadata_numRuns_andEndsPtype() throws Exception {
            // Given
            long[] data = {1L, 1L, 2L, 2L, 3L};

            // When
            EncodeResult result = ENCODER.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            var metaSeg = result.rootNode().metadata();
            ProtoRunEndMetadata meta = ProtoRunEndMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            // Then
            assertThat(meta.num_runs()).isEqualTo(3);
            assertThat(meta.ends_ptype().value()).isEqualTo(2);
        }
    }
}
