package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.ListMetadata;
import io.github.dfa1.vortex.reader.decode.ListEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListEncodingEncoderTest {

    private static final ListEncodingEncoder ENCODER = new ListEncodingEncoder();
    private static final ListEncodingDecoder DECODER = new ListEncodingDecoder();
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
            // Given
            // When
            boolean result = ENCODER.accepts(DTypes.LIST_I32);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void accepts_primitiveDtype_false() {
            // Given
            // When
            boolean result = ENCODER.accepts(DTypes.I32);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        void encode_producesTwoChildren_noBuffers() {
            // Given
            int[] elements = {1, 2, 3, 4, 5};
            long[] offsets = {0, 2, 5};
            ListData data = new ListData(elements, offsets, 2);

            // When
            EncodeResult result = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_LIST);
            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.rootNode().children()).hasSize(2);
        }
    }

    @Nested
    class Decode {

        @Test
        void roundTrip_i32Elements_preservesValues() {
            // Given
            int[] elements = {10, 20, 30, 40, 50};
            long[] offsets = {0, 2, 3, 5};
            ListData data = new ListData(elements, offsets, 3);
            EncodeResult encoded = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = encoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(encoded.rootNode()), DTypes.LIST_I32, 3, bufs, REGISTRY, Arena.global());

            // When
            ListArray result = (ListArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(3);
            IntArray decodedElems = (IntArray) result.elements();
            assertThat(decodedElems.length()).isEqualTo(5);
            for (int i = 0; i < elements.length; i++) {
                assertThat(decodedElems.getInt(i)).isEqualTo(elements[i]);
            }
            LongArray decodedOffsets = (LongArray) result.offsets();
            assertThat(decodedOffsets.length()).isEqualTo(4);
            for (int i = 0; i < offsets.length; i++) {
                assertThat(decodedOffsets.getLong(i)).isEqualTo(offsets[i]);
            }
        }

        @Test
        void roundTrip_emptyLists_preservesOffsets() {
            // Given
            int[] elements = {};
            long[] offsets = {0, 0, 0};
            ListData data = new ListData(elements, offsets, 2);
            EncodeResult encoded = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = encoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(encoded.rootNode()), DTypes.LIST_I32, 2, bufs, REGISTRY, Arena.global());

            // When
            ListArray result = (ListArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(2);
            assertThat(result.elements().length()).isEqualTo(0);
            assertThat(result.offsets().length()).isEqualTo(3);
        }

        @Test
        void roundTrip_singleList_preservesValues() {
            // Given
            int[] elements = {7, 8, 9};
            long[] offsets = {0, 3};
            ListData data = new ListData(elements, offsets, 1);
            EncodeResult encoded = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = encoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(encoded.rootNode()), DTypes.LIST_I32, 1, bufs, REGISTRY, Arena.global());

            // When
            ListArray result = (ListArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(1);
            IntArray decodedElems = (IntArray) result.elements();
            assertThat(decodedElems.length()).isEqualTo(3);
            for (int i = 0; i < elements.length; i++) {
                assertThat(decodedElems.getInt(i)).isEqualTo(elements[i]);
            }
        }

        @Test
        void decode_wrongDtype_throws() {
            // Given
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_LIST, null,
                    new ArrayNode[0], new int[0]);
            DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0, new MemorySegment[0], REGISTRY, Arena.global());

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx)).hasMessageContaining("DType.List");
        }

        @Test
        void decode_wrongChildCount_throws() {
            // Given
            ArrayNode child = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[0]);
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_LIST,
                    java.nio.ByteBuffer.wrap(new byte[0]),
                    new ArrayNode[]{child}, new int[0]);
            DecodeContext ctx = new DecodeContext(node, DTypes.LIST_I32, 0, new MemorySegment[0], REGISTRY, Arena.global());

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx)).hasMessageContaining("expected 2 or 3 children");
        }
    }

    @Nested
    class Metadata {

        @Test
        void encode_metadata_elementsLen_matchesElementCount() throws Exception {
            // Given
            int[] elements = {1, 2, 3, 4, 5};
            long[] offsets = {0L, 2L, 5L};
            ListData data = new ListData(elements, offsets, 2);

            // When
            EncodeResult result = ENCODER.encode(DTypes.LIST_I32, data, EncodeTestHelper.testCtx());

            // Then
            var metaSeg = java.lang.foreign.MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
            ListMetadata meta = ListMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(meta.elements_len()).isEqualTo(5);
        }
    }
}
