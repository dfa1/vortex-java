package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.proto.DTypeProtos;
import io.github.dfa1.vortex.proto.EncodingProtos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RunEndEncodingTest {

    @Nested
    class Decode {

        private static DecodeContext buildCtx(
                DType dtype, long rowCount,
                long[] ends, long[] values, PType endsPtype, long offset
        ) {
            byte[] metaBytes = EncodingProtos.RunEndMetadata.newBuilder()
                                       .setEndsPtype(DTypeProtos.PType.forNumber(endsPtype.ordinal()))
                                       .setNumRuns(ends.length)
                                       .setOffset(offset)
                                       .build()
                                       .toByteArray();

            byte[] endsBuf = toLEBytes(ends, endsPtype);
            byte[] valBuf = toLEBytes(values, PType.I64);

            ArrayNode endsNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{0}, ArrayStats.empty());
            ArrayNode valsNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{1}, ArrayStats.empty());
            ArrayNode reNode = ArrayNode.of(EncodingId.VORTEX_RUNEND,
                    ByteBuffer.wrap(metaBytes),
                    new ArrayNode[]{endsNode, valsNode},
                    new int[0], ArrayStats.empty());

            MemorySegment[] segments = {
                    MemorySegment.ofArray(endsBuf),
                    MemorySegment.ofArray(valBuf)
            };

            EncodingRegistry registry = TestRegistry.of(new RunEndEncoding(), new PrimitiveEncoding());

            return new DecodeContext(reNode, dtype, rowCount, segments, registry, java.lang.foreign.Arena.global());
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
            // Given — 1 run: ends=[5], values=[42]; output = [42, 42, 42, 42, 42]
            long[] ends = {5L};
            long[] values = {42L};
            DecodeContext ctx = buildCtx(DTypes.I64, 5, ends, values, PType.U32, 0L);
            RunEndEncoding sut = new RunEndEncoding();

            // When
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(5L);
            var layout = PTypeIO.LE_LONG;
            for (int i = 0; i < 5; i++) {
                assertThat(result.segment().get(layout, (long) i * 8)).isEqualTo(42L);
            }
        }

        @Test
        void decode_multipleRuns_expandsCorrectly() {
            // Given — runs: [0,2)=10, [2,5)=20, [5,7)=30
            // ends=[2,5,7], values=[10,20,30]
            long[] ends = {2L, 5L, 7L};
            long[] values = {10L, 20L, 30L};
            DecodeContext ctx = buildCtx(DTypes.I64, 7, ends, values, PType.U32, 0L);
            RunEndEncoding sut = new RunEndEncoding();

            // When
            Array result = sut.decode(ctx);

            // Then
            long[] expected = {10, 10, 20, 20, 20, 30, 30};
            var layout = PTypeIO.LE_LONG;
            for (int i = 0; i < expected.length; i++) {
                assertThat(result.segment().get(layout, (long) i * 8))
                        .as("index %d", i).isEqualTo(expected[i]);
            }
        }

        @Test
        void decode_withOffset_skipsLogicalElements() {
            // Given — logical array: [0,3)=10, [3,6)=20; offset=2, rowCount=3
            // output elements [2..5): [10, 20, 20]
            long[] ends = {3L, 6L};
            long[] values = {10L, 20L};
            DecodeContext ctx = buildCtx(DTypes.I64, 3, ends, values, PType.U32, 2L);
            RunEndEncoding sut = new RunEndEncoding();

            // When
            Array result = sut.decode(ctx);

            // Then
            long[] expected = {10L, 20L, 20L};
            var layout = PTypeIO.LE_LONG;
            for (int i = 0; i < expected.length; i++) {
                assertThat(result.segment().get(layout, (long) i * 8))
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
            var sut = new RunEndEncoding();
            EncodingRegistry registry = TestRegistry.withPrimitive(sut);
            var le = PTypeIO.LE_LONG;

            // When
            EncodeResult encoded = sut.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, data.length, DTypes.I64, registry);
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            for (int i = 0; i < data.length; i++) {
                assertThat(result.segment().get(le, (long) i * 8)).as("index %d", i).isEqualTo(data[i]);
            }
        }

        @Test
        void encode_i64_metadata_numRuns_andEndsPtype() throws Exception {
            // Given — 3 runs; if tag drifts, num_runs reads as 0 or ends_ptype reads as 0 (U8)
            long[] data = {1L, 1L, 2L, 2L, 3L};
            RunEndEncoding sut = new RunEndEncoding();

            // When
            EncodeResult result = sut.encode(DTypes.I64, data, EncodeTestHelper.testCtx());
            EncodingProtos.RunEndMetadata meta =
                    EncodingProtos.RunEndMetadata.parseFrom(result.rootNode().metadata().duplicate());

            // Then
            assertThat(meta.getNumRuns()).isEqualTo(3);
            assertThat(meta.getEndsPtypeValue()).isEqualTo(2); // U32
        }
    }
}
