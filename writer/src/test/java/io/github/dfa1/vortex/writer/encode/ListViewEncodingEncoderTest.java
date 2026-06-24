package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestDecodeContexts;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.ProtoListViewMetadata;
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
        return ArrayNode.of(node.encodingId(), node.metadata(), children, node.bufferIndices());
    }

    @Nested
    class Encode {

        @Test
        void accepts_listDtype_true() {
            // Given / When / Then
            assertThat(ENCODER.accepts(DTypes.LIST_I32)).isTrue();
        }

        @Test
        void accepts_primitiveDtype_false() {
            // Given / When / Then
            assertThat(ENCODER.accepts(DTypes.I32)).isFalse();
        }

        @Test
        void encode_producesThreeChildren_noBuffers() {
            // Given
            int[] elements = {1, 2, 3, 4, 5};
            int[] offsets = {0, 2};
            int[] sizes = {2, 3};
            ListViewData data = new ListViewData(elements, offsets, sizes, 2);

            // When
            EncodeResult result = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_LISTVIEW);
            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.rootNode().children()).hasSize(3);
        }
    }

    @Nested
    class Decode {

        @Test
        void roundTrip_i32Elements_preservesValues() {
            // Given
            int[] elements = {10, 20, 30, 40, 50};
            int[] offsets = {0, 2, 3};
            int[] sizes = {2, 1, 2};
            ListViewData data = new ListViewData(elements, offsets, sizes, 3);

            // When
            EncodeResult resultEncoded = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = resultEncoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(resultEncoded.rootNode()), DTypes.LIST_I32, 3, bufs, REGISTRY, Arena.global());
            ListViewArray resultDecoded = (ListViewArray) DECODER.decode(ctx);

            // Then
            assertThat(resultDecoded.length()).isEqualTo(3);
            IntArray decodedElems = (IntArray) resultDecoded.elements();
            assertThat(decodedElems.length()).isEqualTo(5);
            for (int i = 0; i < elements.length; i++) {
                assertThat(decodedElems.getInt(i)).isEqualTo(elements[i]);
            }
            IntArray decodedOffsets = (IntArray) resultDecoded.offsets();
            assertThat(decodedOffsets.length()).isEqualTo(3);
            IntArray decodedSizes = (IntArray) resultDecoded.sizes();
            assertThat(decodedSizes.length()).isEqualTo(3);
        }

        @Test
        void roundTrip_emptyLists_preservesZeroSizes() {
            // Given
            int[] elements = {};
            int[] offsets = {0, 0};
            int[] sizes = {0, 0};
            ListViewData data = new ListViewData(elements, offsets, sizes, 2);

            // When
            EncodeResult resultEncoded = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = resultEncoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(resultEncoded.rootNode()), DTypes.LIST_I32, 2, bufs, REGISTRY, Arena.global());
            ListViewArray resultDecoded = (ListViewArray) DECODER.decode(ctx);

            // Then
            assertThat(resultDecoded.length()).isEqualTo(2);
            assertThat(resultDecoded.elements().length()).isZero();
            assertThat(resultDecoded.offsets().length()).isEqualTo(2);
            assertThat(resultDecoded.sizes().length()).isEqualTo(2);
        }

        @Test
        void roundTrip_singleList_preservesValues() {
            // Given
            int[] elements = {7, 8, 9};
            int[] offsets = {0};
            int[] sizes = {3};
            ListViewData data = new ListViewData(elements, offsets, sizes, 1);

            // When
            EncodeResult resultEncoded = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = resultEncoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(resultEncoded.rootNode()), DTypes.LIST_I32, 1, bufs, REGISTRY, Arena.global());
            ListViewArray resultDecoded = (ListViewArray) DECODER.decode(ctx);

            // Then
            assertThat(resultDecoded.length()).isEqualTo(1);
            IntArray decodedElems = (IntArray) resultDecoded.elements();
            assertThat(decodedElems.length()).isEqualTo(3);
            for (int i = 0; i < elements.length; i++) {
                assertThat(decodedElems.getInt(i)).isEqualTo(elements[i]);
            }
        }

        @Test
        void decode_wrongDtype_throws() {
            // Given
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_LISTVIEW, null,
                    new ArrayNode[0], new int[0]);
            DecodeContext ctx = TestDecodeContexts.of(node, DTypes.I32).registry(REGISTRY).build();

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx)).hasMessageContaining("DType.List");
        }

        @Test
        void decode_wrongChildCount_throws() {
            // Given
            ArrayNode child = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[0]);
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_LISTVIEW,
                    MemorySegment.ofArray(new byte[0]),
                    new ArrayNode[]{child}, new int[0]);
            DecodeContext ctx = TestDecodeContexts.of(node, DTypes.LIST_I32).registry(REGISTRY).build();

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx)).hasMessageContaining("expected 3 or 4 children");
        }
    }

    @Nested
    class Metadata {

        @Test
        void encode_metadata_elementsLen_matchesElementCount() throws Exception {
            // Given
            int[] elements = {1, 2, 3, 4, 5};
            int[] offsets = {0, 2};
            int[] sizes = {2, 3};
            ListViewData data = new ListViewData(elements, offsets, sizes, 2);

            // When
            EncodeResult result = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            java.lang.foreign.MemorySegment metaSeg = result.rootNode().metadata();
            ProtoListViewMetadata meta = ProtoListViewMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            // Then
            assertThat(meta.elements_len()).isEqualTo(5);
        }
    }
}
