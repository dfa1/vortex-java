package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.reader.decode.ChunkedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkedEncodingEncoderTest {

    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final ChunkedEncodingEncoder ENCODER = new ChunkedEncodingEncoder();
    private static final ChunkedEncodingDecoder DECODER = new ChunkedEncodingDecoder();
    private static final PrimitiveEncodingEncoder PRIM_ENCODER = new PrimitiveEncodingEncoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    private static ArrayNode toArrayNode(EncodeNode enc) {
        ArrayNode[] children = new ArrayNode[enc.children().length];
        for (int i = 0; i < children.length; i++) {
            children[i] = toArrayNode(enc.children()[i]);
        }
        return ArrayNode.of(enc.encodingId(), enc.metadata(), children, enc.bufferIndices());
    }

    private static EncodeNode remapped(EncodeNode node, int offset) {
        return EncodeNode.remapBufferIndices(node, offset);
    }

    @Nested
    class Encode {

        @Test
        void roundTrip_twoChunks_i64_preservesValues() {
            long[] chunk0 = {10L, 20L, 30L};
            long[] chunk1 = {40L, 50L};
            DType i64 = new DType.Primitive(PType.I64, false);
            ChunkedData data = new ChunkedData(List.of(chunk0, chunk1), new long[]{3, 2});

            EncodeResult encoded = ENCODER.encode(i64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, 5L, i64, REGISTRY);
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(5);
            assertThat(ArraySegments.of(result).get(LE_LONG, 0L)).isEqualTo(10L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 8L)).isEqualTo(20L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 16L)).isEqualTo(30L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 24L)).isEqualTo(40L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 32L)).isEqualTo(50L);
        }

        @Test
        void encodeNode_hasNoDirectBuffers_offsetsAsFirstChild() {
            long[] chunk0 = {1L, 2L};
            DType i64 = new DType.Primitive(PType.I64, false);
            ChunkedData data = new ChunkedData(List.of(chunk0), new long[]{2});

            EncodeResult result = ENCODER.encode(i64, data, EncodeTestHelper.testCtx());

            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.rootNode().children()).hasSize(2);
            assertThat(result.buffers()).hasSize(2);
        }

        @Test
        void mismatchedLengths_throws() {
            assertThatThrownBy(() -> new ChunkedData(List.of(new long[]{1L}), new long[]{1, 2}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Decode {

        @Test
        void roundTrip_twoChunks_concatenatesValues() {
            long[] chunk0 = {10L, 20L, 30L};
            long[] chunk1 = {40L, 50L};
            DType i64 = new DType.Primitive(PType.I64, false);
            DType u64 = new DType.Primitive(PType.U64, false);

            EncodeResult offsetsResult = PRIM_ENCODER.encode(u64, new long[]{0L, 3L, 5L}, EncodeTestHelper.testCtx());
            EncodeResult chunk0Result = PRIM_ENCODER.encode(i64, chunk0, EncodeTestHelper.testCtx());
            EncodeResult chunk1Result = PRIM_ENCODER.encode(i64, chunk1, EncodeTestHelper.testCtx());

            MemorySegment[] allBufs = {
                    offsetsResult.buffers().getFirst(),
                    chunk0Result.buffers().getFirst(),
                    chunk1Result.buffers().getFirst()
            };

            ArrayNode offsetsNode = toArrayNode(offsetsResult.rootNode());
            ArrayNode chunk0Node = toArrayNode(remapped(chunk0Result.rootNode(), 1));
            ArrayNode chunk1Node = toArrayNode(remapped(chunk1Result.rootNode(), 2));
            ArrayNode root = ArrayNode.of(
                    EncodingId.VORTEX_CHUNKED, null,
                    new ArrayNode[]{offsetsNode, chunk0Node, chunk1Node},
                    new int[]{});

            DecodeContext ctx = new DecodeContext(root, i64, 5L, allBufs, REGISTRY, Arena.ofAuto());
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(5);
            assertThat(ArraySegments.of(result).get(LE_LONG, 0L)).isEqualTo(10L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 8L)).isEqualTo(20L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 16L)).isEqualTo(30L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 24L)).isEqualTo(40L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 32L)).isEqualTo(50L);
        }

        @Test
        void singleChunk_returnsSameValues() {
            long[] data = {1L, 2L, 3L};
            DType i64 = new DType.Primitive(PType.I64, false);
            DType u64 = new DType.Primitive(PType.U64, false);

            EncodeResult offsetsResult = PRIM_ENCODER.encode(u64, new long[]{0L, 3L}, EncodeTestHelper.testCtx());
            EncodeResult chunkResult = PRIM_ENCODER.encode(i64, data, EncodeTestHelper.testCtx());

            MemorySegment[] allBufs = {
                    offsetsResult.buffers().getFirst(),
                    chunkResult.buffers().getFirst()
            };

            ArrayNode root = ArrayNode.of(
                    EncodingId.VORTEX_CHUNKED, null,
                    new ArrayNode[]{toArrayNode(offsetsResult.rootNode()), toArrayNode(remapped(chunkResult.rootNode(), 1))},
                    new int[]{});

            DecodeContext ctx = new DecodeContext(root, i64, 3L, allBufs, REGISTRY, Arena.ofAuto());
            Array result = DECODER.decode(ctx);

            assertThat(result.length()).isEqualTo(3);
            for (int i = 0; i < 3; i++) {
                assertThat(ArraySegments.of(result).get(LE_LONG, (long) i * 8)).isEqualTo(data[i]);
            }
        }

        @Test
        void noChildren_throws() {
            DType i64 = new DType.Primitive(PType.I64, false);
            ArrayNode root = ArrayNode.of(EncodingId.VORTEX_CHUNKED, null, new ArrayNode[]{}, new int[]{});
            DecodeContext ctx = new DecodeContext(root, i64, 0L, new MemorySegment[]{}, REGISTRY, Arena.ofAuto());

            assertThatThrownBy(() -> DECODER.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("at least one child");
        }
    }
}
