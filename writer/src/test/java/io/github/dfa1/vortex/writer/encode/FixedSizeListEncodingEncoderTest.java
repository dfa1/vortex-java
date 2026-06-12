package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.FixedSizeListArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
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
        return ArrayNode.of(node.encodingId(), node.metadata(), children, node.bufferIndices(), ArrayStats.empty());
    }

    @Nested
    class Encode {

        @Test
        void accepts_fixedSizeListDtype_true() {
            DType.FixedSizeList dtype = new DType.FixedSizeList(DTypes.I32, 3, false);
            assertThat(ENCODER.accepts(dtype)).isTrue();
        }

        @Test
        void accepts_primitiveDtype_false() {
            assertThat(ENCODER.accepts(DTypes.I32)).isFalse();
        }

        @Test
        void encode_producesOneChild_noBuffers() {
            DType.FixedSizeList dtype = new DType.FixedSizeList(DTypes.I32, 2, false);
            int[] elements = {1, 2, 3, 4};
            FixedSizeListData data = new FixedSizeListData(elements, 2);

            EncodeResult result = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());

            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_FIXED_SIZE_LIST);
            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.rootNode().children()).hasSize(1);
        }
    }

    @Nested
    class Decode {

        @Test
        void roundTrip_i32Elements_preservesValues() {
            DType.FixedSizeList dtype = new DType.FixedSizeList(DTypes.I32, 3, false);
            int[] elements = {10, 20, 30, 40, 50, 60};
            FixedSizeListData data = new FixedSizeListData(elements, 2);

            EncodeResult result = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), dtype, 2, bufs, REGISTRY, Arena.global());
            FixedSizeListArray decoded = (FixedSizeListArray) DECODER.decode(ctx);

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
            DType.FixedSizeList dtype = new DType.FixedSizeList(DTypes.I32, 1, false);
            int[] elements = {7, 8, 9};
            FixedSizeListData data = new FixedSizeListData(elements, 3);

            EncodeResult result = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), dtype, 3, bufs, REGISTRY, Arena.global());
            FixedSizeListArray decoded = (FixedSizeListArray) DECODER.decode(ctx);

            assertThat(decoded.length()).isEqualTo(3);
            assertThat(decoded.fixedSize()).isEqualTo(1);
            IntArray elems = (IntArray) decoded.elements();
            for (int i = 0; i < elements.length; i++) {
                assertThat(elems.getInt(i)).isEqualTo(elements[i]);
            }
        }

        @Test
        void decode_wrongDtype_throws() {
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_FIXED_SIZE_LIST, null,
                    new ArrayNode[0], new int[0], ArrayStats.empty());
            DecodeContext ctx = new DecodeContext(node, DTypes.I32, 0, new MemorySegment[0], REGISTRY, Arena.global());

            assertThatThrownBy(() -> DECODER.decode(ctx))
                    .hasMessageContaining("DType.FixedSizeList");
        }
    }
}
