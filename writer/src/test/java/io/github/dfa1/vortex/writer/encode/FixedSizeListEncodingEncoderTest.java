package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.reader.decode.FixedSizeListEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedSizeListEncodingEncoderTest {

    private static final FixedSizeListEncodingEncoder ENCODER = new FixedSizeListEncodingEncoder();
    private static final FixedSizeListEncodingDecoder DECODER = new FixedSizeListEncodingDecoder();
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
        void accepts_fixedSizeListDtype_true() {
            // Given
            DType.FixedSizeList dtype = new DType.FixedSizeList(DTypes.I32, 3, false);

            // When / Then
            assertThat(ENCODER.accepts(dtype)).isTrue();
        }

        @Test
        void accepts_primitiveDtype_false() {
            // Given / When / Then
            assertThat(ENCODER.accepts(DTypes.I32)).isFalse();
        }

        @Test
        void encode_producesOneChild_noBuffers() {
            // Given
            DType.FixedSizeList dtype = new DType.FixedSizeList(DTypes.I32, 2, false);
            int[] elements = {1, 2, 3, 4};
            FixedSizeListData data = new FixedSizeListData(elements, 2);

            // When
            EncodeResult result = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_FIXED_SIZE_LIST);
            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.rootNode().children()).hasSize(1);
        }
    }

    @Nested
    class Decode {

        @Test
        void roundTrip_i32Elements_preservesValues() {
            // Given
            DType.FixedSizeList dtype = new DType.FixedSizeList(DTypes.I32, 3, false);
            int[] elements = {10, 20, 30, 40, 50, 60};
            FixedSizeListData data = new FixedSizeListData(elements, 2);

            // When
            EncodeResult resultEncoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = resultEncoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(resultEncoded.rootNode()), dtype, 2, bufs, REGISTRY, Arena.global());
            FixedSizeListArray resultDecoded = (FixedSizeListArray) DECODER.decode(ctx);

            // Then
            assertThat(resultDecoded.length()).isEqualTo(2);
            assertThat(resultDecoded.fixedSize()).isEqualTo(3);
            IntArray elems = (IntArray) resultDecoded.elements();
            assertThat(elems.length()).isEqualTo(6);
            for (int i = 0; i < elements.length; i++) {
                assertThat(elems.getInt(i)).isEqualTo(elements[i]);
            }
        }

        @Test
        void roundTrip_fixedSizeOne_preservesValues() {
            // Given
            DType.FixedSizeList dtype = new DType.FixedSizeList(DTypes.I32, 1, false);
            int[] elements = {7, 8, 9};
            FixedSizeListData data = new FixedSizeListData(elements, 3);

            // When
            EncodeResult resultEncoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = resultEncoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(resultEncoded.rootNode()), dtype, 3, bufs, REGISTRY, Arena.global());
            FixedSizeListArray resultDecoded = (FixedSizeListArray) DECODER.decode(ctx);

            // Then
            assertThat(resultDecoded.length()).isEqualTo(3);
            assertThat(resultDecoded.fixedSize()).isEqualTo(1);
            IntArray elems = (IntArray) resultDecoded.elements();
            for (int i = 0; i < elements.length; i++) {
                assertThat(elems.getInt(i)).isEqualTo(elements[i]);
            }
        }

        @Test
        void decode_wrongDtype_throws() {
            // Given
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_FIXED_SIZE_LIST, null,
                    new ArrayNode[0], new int[0]);
            DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0, new MemorySegment[0], REGISTRY, Arena.global());

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx))
                    .hasMessageContaining("DType.FixedSizeList");
        }
    }
}
