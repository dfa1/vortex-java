package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkedEncodingTest {

    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static ArrayNode toArrayNode(EncodeNode enc) {
        ArrayNode[] children = new ArrayNode[enc.children().length];
        for (int i = 0; i < children.length; i++) {
            children[i] = toArrayNode(enc.children()[i]);
        }
        return ArrayNode.of(enc.encodingId(), enc.metadata(), children, enc.bufferIndices(), null);
    }

    private static EncodeNode remapped(EncodeNode node, int offset) {
        return EncodeNode.remapBufferIndices(node, offset);
    }

    @Nested
    class Encode {

        @Test
        void roundTrip_twoChunks_i64_preservesValues() {
            // Given
            long[] chunk0 = {10L, 20L, 30L};
            long[] chunk1 = {40L, 50L};
            DType i64 = new DType.Primitive(PType.I64, false);
            var sut = new ChunkedEncoding();
            Registry registry = Registry.builder()
                    .register(sut)
                    .register(new PrimitiveEncoding())
                    .build();
            ChunkedData data = new ChunkedData(List.of(chunk0, chunk1), new long[]{3, 2});

            // When
            EncodeResult encoded = sut.encode(i64, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = EncodeTestHelper.toDecodeContext(encoded, 5L, i64, registry);
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(5);
            assertThat(ArraySegments.of(result).get(LE_LONG, 0L)).isEqualTo(10L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 8L)).isEqualTo(20L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 16L)).isEqualTo(30L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 24L)).isEqualTo(40L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 32L)).isEqualTo(50L);
        }

        @Test
        void encodeNode_hasNoDirectBuffers_offsetsAsFirstChild() {
            // Given
            long[] chunk0 = {1L, 2L};
            DType i64 = new DType.Primitive(PType.I64, false);
            var sut = new ChunkedEncoding();
            ChunkedData data = new ChunkedData(List.of(chunk0), new long[]{2});

            // When
            EncodeResult result = sut.encode(i64, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.rootNode().children()).hasSize(2); // offsets + 1 chunk
            assertThat(result.buffers()).hasSize(2); // offsets buf + chunk buf
        }

        @Test
        void mismatchedLengths_throws() {
            // Given
            var sut = new ChunkedEncoding();
            DType i64 = new DType.Primitive(PType.I64, false);

            // When / Then
            assertThatThrownBy(() -> new ChunkedData(List.of(new long[]{1L}), new long[]{1, 2}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Decode {

        @Test
        void roundTrip_twoChunks_concatenatesValues() {
            // Given
            long[] chunk0 = {10L, 20L, 30L};
            long[] chunk1 = {40L, 50L};
            DType i64 = new DType.Primitive(PType.I64, false);
            DType u64 = new DType.Primitive(PType.U64, false);

            var sut = new ChunkedEncoding();
            Registry registry = Registry.builder()
                    .register(sut)
                    .register(new PrimitiveEncoding())
                    .build();

            // Build chunk_offsets segment: [0, 3, 5] as U64 LE
            EncodeResult offsetsResult = new PrimitiveEncoding().encode(u64, new long[]{0L, 3L, 5L}, EncodeTestHelper.testCtx());
            // Build chunk0 segment
            EncodeResult chunk0Result = new PrimitiveEncoding().encode(i64, chunk0, EncodeTestHelper.testCtx());
            // Build chunk1 segment
            EncodeResult chunk1Result = new PrimitiveEncoding().encode(i64, chunk1, EncodeTestHelper.testCtx());

            // Collect all buffers: [offsets_buf, chunk0_buf, chunk1_buf]
            MemorySegment[] allBufs = {
                    offsetsResult.buffers().getFirst(),
                    chunk0Result.buffers().getFirst(),
                    chunk1Result.buffers().getFirst()
            };

            // Build ArrayNode tree:
            //   root: ChunkedEncoding, children=[offsetsNode, chunk0Node, chunk1Node], buffers=[]
            //   offsetsNode: PrimitiveEncoding, bufferIndices=[0]
            //   chunk0Node:  PrimitiveEncoding, bufferIndices=[1]
            //   chunk1Node:  PrimitiveEncoding, bufferIndices=[2]
            ArrayNode offsetsNode = toArrayNode(offsetsResult.rootNode());
            ArrayNode chunk0Node = toArrayNode(remapped(chunk0Result.rootNode(), 1));
            ArrayNode chunk1Node = toArrayNode(remapped(chunk1Result.rootNode(), 2));
            ArrayNode root = ArrayNode.of(
                    EncodingId.VORTEX_CHUNKED, null,
                    new ArrayNode[]{offsetsNode, chunk0Node, chunk1Node},
                    new int[]{}, null);

            DecodeContext ctx = new DecodeContext(root, i64, 5L, allBufs, registry, Arena.ofAuto());

            // When
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(5);
            assertThat(ArraySegments.of(result).get(LE_LONG, 0L)).isEqualTo(10L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 8L)).isEqualTo(20L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 16L)).isEqualTo(30L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 24L)).isEqualTo(40L);
            assertThat(ArraySegments.of(result).get(LE_LONG, 32L)).isEqualTo(50L);
        }

        @Test
        void singleChunk_returnsSameValues() {
            // Given
            long[] data = {1L, 2L, 3L};
            DType i64 = new DType.Primitive(PType.I64, false);
            DType u64 = new DType.Primitive(PType.U64, false);

            Registry registry = Registry.builder()
                    .register(new ChunkedEncoding())
                    .register(new PrimitiveEncoding())
                    .build();

            EncodeResult offsetsResult = new PrimitiveEncoding().encode(u64, new long[]{0L, 3L}, EncodeTestHelper.testCtx());
            EncodeResult chunkResult = new PrimitiveEncoding().encode(i64, data, EncodeTestHelper.testCtx());

            MemorySegment[] allBufs = {
                    offsetsResult.buffers().getFirst(),
                    chunkResult.buffers().getFirst()
            };

            ArrayNode root = ArrayNode.of(
                    EncodingId.VORTEX_CHUNKED, null,
                    new ArrayNode[]{toArrayNode(offsetsResult.rootNode()), toArrayNode(remapped(chunkResult.rootNode(), 1))},
                    new int[]{}, null);

            DecodeContext ctx = new DecodeContext(root, i64, 3L, allBufs, registry, Arena.ofAuto());

            // When
            Array result = new ChunkedEncoding().decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(3);
            for (int i = 0; i < 3; i++) {
                assertThat(ArraySegments.of(result).get(LE_LONG, (long) i * 8)).isEqualTo(data[i]);
            }
        }

        @Test
        void noChildren_throws() {
            // Given
            DType i64 = new DType.Primitive(PType.I64, false);
            Registry registry = Registry.builder()
                    .register(new ChunkedEncoding())
                    .build();
            ArrayNode root = ArrayNode.of(EncodingId.VORTEX_CHUNKED, null, new ArrayNode[]{}, new int[]{}, null);
            DecodeContext ctx = new DecodeContext(root, i64, 0L, new MemorySegment[]{}, registry, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> new ChunkedEncoding().decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("at least one child");
        }
    }
}
