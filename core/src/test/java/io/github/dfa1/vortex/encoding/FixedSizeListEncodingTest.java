package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.FixedSizeListArray;
import io.github.dfa1.vortex.core.array.IntArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedSizeListEncodingTest {


    private static ArrayNode toArrayNode(EncodeNode node) {
        ArrayNode[] children = new ArrayNode[node.children().length];
        for (int i = 0; i < children.length; i++) {
            children[i] = toArrayNode(node.children()[i]);
        }
        return ArrayNode.of(node.encodingId(), node.metadata(), children, node.bufferIndices(), ArrayStats.empty());
    }

    private static Registry registry() {
        return Registry.builder()
                .register(new FixedSizeListEncoding())
                .register(new PrimitiveEncoding())
                .build();
    }

    @Nested
    class Encode {

        @Test
        void accepts_fixedSizeListDtype_true() {
            // Given
            FixedSizeListEncoding sut = new FixedSizeListEncoding();
            DType.FixedSizeList dtype = new DType.FixedSizeList(DTypes.I32, 3, false);

            // When / Then
            assertThat(sut.accepts(dtype)).isTrue();
        }

        @Test
        void accepts_primitiveDtype_false() {
            // Given
            FixedSizeListEncoding sut = new FixedSizeListEncoding();

            // When / Then
            assertThat(sut.accepts(DTypes.I32)).isFalse();
        }

        @Test
        void encode_producesOneChild_noBuffers() {
            // Given
            DType.FixedSizeList dtype = new DType.FixedSizeList(DTypes.I32, 2, false);
            int[] elements = {1, 2, 3, 4};
            FixedSizeListData data = new FixedSizeListData(elements, 2);
            FixedSizeListEncoding sut = new FixedSizeListEncoding();

            // When
            EncodeResult result = sut.encode(dtype, data, EncodeTestHelper.testCtx());

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
            FixedSizeListEncoding sut = new FixedSizeListEncoding();

            // When
            EncodeResult result = sut.encode(dtype, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), dtype, 2, bufs, registry(), Arena.global());
            FixedSizeListArray decoded = (FixedSizeListArray) sut.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(2);
            assertThat(decoded.fixedSize()).isEqualTo(3);
            IntArray elems = (IntArray) decoded.elements();
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
            FixedSizeListEncoding sut = new FixedSizeListEncoding();

            // When
            EncodeResult result = sut.encode(dtype, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), dtype, 3, bufs, registry(), Arena.global());
            FixedSizeListArray decoded = (FixedSizeListArray) sut.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(3);
            assertThat(decoded.fixedSize()).isEqualTo(1);
            IntArray elems = (IntArray) decoded.elements();
            for (int i = 0; i < elements.length; i++) {
                assertThat(elems.getInt(i)).isEqualTo(elements[i]);
            }
        }

        @Test
        void decode_wrongDtype_throws() {
            // Given
            FixedSizeListEncoding sut = new FixedSizeListEncoding();
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_FIXED_SIZE_LIST, null,
                    new ArrayNode[0], new int[0], ArrayStats.empty());
            DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0, new MemorySegment[0], registry(), Arena.global());

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .hasMessageContaining("DType.FixedSizeList");
        }
    }
}
