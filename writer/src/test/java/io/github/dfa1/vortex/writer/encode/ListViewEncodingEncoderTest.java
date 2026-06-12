package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.ListViewArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestDecodeContexts;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.ListViewMetadata;
import io.github.dfa1.vortex.reader.decode.ListViewEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListViewEncodingEncoderTest {

    private static final ListViewEncodingEncoder ENCODER = new ListViewEncodingEncoder();
    private static final ListViewEncodingDecoder DECODER = new ListViewEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    private static ArrayNode toArrayNode(EncodeNode node) {
        ArrayNode[] children = new ArrayNode[node.children().length];
        for (int i = 0; i < children.length; i++) {
            children[i] = toArrayNode(node.children()[i]);
        }
        return ArrayNode.of(node.encodingId(), node.metadata(), children, node.bufferIndices(), ArrayStats.empty());
    }

    @Nested
    class Encode {

        @Test
        void accepts_listDtype_true() {
            assertThat(ENCODER.accepts(DTypes.LIST_I32)).isTrue();
        }

        @Test
        void accepts_primitiveDtype_false() {
            assertThat(ENCODER.accepts(DTypes.I32)).isFalse();
        }

        @Test
        void encode_producesThreeChildren_noBuffers() {
            int[] elements = {1, 2, 3, 4, 5};
            int[] offsets = {0, 2};
            int[] sizes = {2, 3};
            ListViewData data = new ListViewData(elements, offsets, sizes, 2);

            EncodeResult result = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());

            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_LISTVIEW);
            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.rootNode().children()).hasSize(3);
        }
    }

    @Nested
    class Decode {

        @Test
        void roundTrip_i32Elements_preservesValues() {
            int[] elements = {10, 20, 30, 40, 50};
            int[] offsets = {0, 2, 3};
            int[] sizes = {2, 1, 2};
            ListViewData data = new ListViewData(elements, offsets, sizes, 3);

            EncodeResult result = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), DTypes.LIST_I32, 3, bufs, REGISTRY, Arena.global());
            ListViewArray decoded = (ListViewArray) DECODER.decode(ctx);

            assertThat(decoded.length()).isEqualTo(3);
            IntArray decodedElems = (IntArray) decoded.elements();
            assertThat(decodedElems.length()).isEqualTo(5);
            for (int i = 0; i < elements.length; i++) {
                assertThat(decodedElems.getInt(i)).isEqualTo(elements[i]);
            }
            IntArray decodedOffsets = (IntArray) decoded.offsets();
            assertThat(decodedOffsets.length()).isEqualTo(3);
            IntArray decodedSizes = (IntArray) decoded.sizes();
            assertThat(decodedSizes.length()).isEqualTo(3);
        }

        @Test
        void roundTrip_emptyLists_preservesZeroSizes() {
            int[] elements = {};
            int[] offsets = {0, 0};
            int[] sizes = {0, 0};
            ListViewData data = new ListViewData(elements, offsets, sizes, 2);

            EncodeResult result = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), DTypes.LIST_I32, 2, bufs, REGISTRY, Arena.global());
            ListViewArray decoded = (ListViewArray) DECODER.decode(ctx);

            assertThat(decoded.length()).isEqualTo(2);
            assertThat(decoded.elements().length()).isEqualTo(0);
            assertThat(decoded.offsets().length()).isEqualTo(2);
            assertThat(decoded.sizes().length()).isEqualTo(2);
        }

        @Test
        void roundTrip_singleList_preservesValues() {
            int[] elements = {7, 8, 9};
            int[] offsets = {0};
            int[] sizes = {3};
            ListViewData data = new ListViewData(elements, offsets, sizes, 1);

            EncodeResult result = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), DTypes.LIST_I32, 1, bufs, REGISTRY, Arena.global());
            ListViewArray decoded = (ListViewArray) DECODER.decode(ctx);

            assertThat(decoded.length()).isEqualTo(1);
            IntArray decodedElems = (IntArray) decoded.elements();
            assertThat(decodedElems.length()).isEqualTo(3);
            for (int i = 0; i < elements.length; i++) {
                assertThat(decodedElems.getInt(i)).isEqualTo(elements[i]);
            }
        }

        @Test
        void decode_wrongDtype_throws() {
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_LISTVIEW, null,
                    new ArrayNode[0], new int[0], ArrayStats.empty());
            DecodeContext ctx = TestDecodeContexts.of(node, DTypes.I32).registry(REGISTRY).build();

            assertThatThrownBy(() -> DECODER.decode(ctx)).hasMessageContaining("DType.List");
        }

        @Test
        void decode_wrongChildCount_throws() {
            ArrayNode child = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[0], ArrayStats.empty());
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_LISTVIEW,
                    java.nio.ByteBuffer.wrap(new byte[0]),
                    new ArrayNode[]{child}, new int[0], ArrayStats.empty());
            DecodeContext ctx = TestDecodeContexts.of(node, DTypes.LIST_I32).registry(REGISTRY).build();

            assertThatThrownBy(() -> DECODER.decode(ctx)).hasMessageContaining("expected 3 or 4 children");
        }
    }

    @Nested
    class Metadata {

        @Test
        void encode_metadata_elementsLen_matchesElementCount() throws Exception {
            int[] elements = {1, 2, 3, 4, 5};
            int[] offsets = {0, 2};
            int[] sizes = {2, 3};
            ListViewData data = new ListViewData(elements, offsets, sizes, 2);

            EncodeResult result = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            java.nio.ByteBuffer metaBuf = result.rootNode().metadata().duplicate();
            java.lang.foreign.MemorySegment metaSeg = java.lang.foreign.MemorySegment.ofBuffer(metaBuf);
            ListViewMetadata meta = ListViewMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(meta.elements_len()).isEqualTo(5);
        }
    }
}
