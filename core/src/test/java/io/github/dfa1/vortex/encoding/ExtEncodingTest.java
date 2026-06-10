package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.LongArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

class ExtEncodingTest {

    @Nested
    class Encode {

        @Test
        void accepts_extensionDtype_returnsTrue() {
            // Given
            DType extDType = new DType.Extension("vortex.timestamp",
                    new DType.Primitive(PType.I64, false), null, false);
            var sut = new ExtEncoding();

            // When / Then
            assertThat(sut.accepts(extDType)).isTrue();
        }

        @Test
        void accepts_primitiveDtype_returnsFalse() {
            // Given
            var sut = new ExtEncoding();

            // When / Then
            assertThat(sut.accepts(new DType.Primitive(PType.I64, false))).isFalse();
        }

        @Test
        void encode_extensionWrappingI64_roundTrips() {
            // Given
            long[] data = {100L, 200L, 300L, 400L};
            DType storageDType = new DType.Primitive(PType.I64, false);
            DType extDType = new DType.Extension("vortex.timestamp", storageDType, null, false);
            var sut = new ExtEncoding();

            // When
            EncodeResult result = sut.encode(extDType, data, EncodeTestHelper.testCtx());

            // Then — root is ext, child is primitive
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_EXT);
            assertThat(result.rootNode().children()).hasSize(1);
            assertThat(result.rootNode().children()[0].encodingId()).isEqualTo(EncodingId.VORTEX_PRIMITIVE);

            // Decode back
            Registry registry = TestRegistry.of(new PrimitiveEncoding(), new ExtEncoding());
            ArrayNode rootNode = encodeNodeToArrayNode(result.rootNode());
            DecodeContext ctx = DecodeContext.ofRawBuffers(
                    rootNode, extDType, data.length,
                    result.buffers().toArray(MemorySegment[]::new),
                    registry, Arena.ofAuto());
            var decoded = sut.decode(ctx);

            assertThat(decoded).isInstanceOf(LongArray.class);
            for (int i = 0; i < data.length; i++) {
                LongArray longArray = (LongArray) decoded;
                assertThat(longArray.getLong(i)).isEqualTo(data[i]);
            }
        }

        private ArrayNode encodeNodeToArrayNode(EncodeNode n) {
            ArrayNode[] children = new ArrayNode[n.children().length];
            for (int i = 0; i < children.length; i++) {
                children[i] = encodeNodeToArrayNode(n.children()[i]);
            }
            return ArrayNode.of(n.encodingId(), n.metadata(), children, n.bufferIndices(), null);
        }
    }

    @Nested
    class Cascade {

        @Test
        void encodeCascade_exposesStorageAsOpenChild() {
            // Given — extension wraps an I64 storage column
            long[] data = {100L, 200L, 300L, 400L};
            DType storageDType = new DType.Primitive(PType.I64, false);
            DType extDType = new DType.Extension("vortex.timestamp", storageDType, null, false);
            var sut = new ExtEncoding();

            // When
            CascadeStep step = sut.encodeCascade(extDType, data, EncodeTestHelper.testCtx());

            // Then — non-terminal step with one open slot for the storage child
            assertThat(step.applicable()).isTrue();
            assertThat(step.isTerminal()).isFalse();
            assertThat(step.openChildren()).hasSize(1);
            ChildSlot slot = step.openChildren().get(0);
            assertThat(slot.childDtype()).isEqualTo(storageDType);
            assertThat(slot.childData()).isSameAs(data);
            assertThat(slot.parentChildIdx()).isEqualTo(0);
            assertThat(step.partialRoot().encodingId()).isEqualTo(EncodingId.VORTEX_EXT);
            assertThat(step.partialRoot().children()).hasSize(1);
        }

        @Test
        void encodeCascade_rejectsNonExtensionDtype() {
            // Given
            var sut = new ExtEncoding();

            // When / Then
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    sut.encodeCascade(new DType.Primitive(PType.I64, false), new long[]{1L},
                            EncodeTestHelper.testCtx()))
                    .isInstanceOf(io.github.dfa1.vortex.core.VortexException.class)
                    .hasMessageContaining("expected extension dtype");
        }
    }

    @Nested
    class Decode {

        @Test
        void decode_extensionWrappingI64_returnsStorageArray() {
            // Given
            long[] values = {10L, 20L, 30L, 40L};
            MemorySegment buf = TestSegments.leLongs(values);

            DType storageDType = new DType.Primitive(PType.I64, false);
            DType extDType = new DType.Extension("vortex.timestamp", storageDType, null, false);

            // child node: vortex.primitive with buffer index 0
            ArrayNode primitiveNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0}, null);
            // parent node: vortex.ext, no buffers, one child
            ArrayNode extNode = ArrayNode.of(EncodingId.VORTEX_EXT, null, new ArrayNode[]{primitiveNode}, new int[0], null);

            Registry registry = TestRegistry.of(new PrimitiveEncoding(), new ExtEncoding());

            DecodeContext ctx = DecodeContext.ofRawBuffers(
                    extNode, extDType, values.length, new MemorySegment[]{buf}, registry, Arena.ofAuto());

            var sut = new ExtEncoding();

            // When
            var result = sut.decode(ctx);

            // Then
            assertThat(result).isInstanceOf(LongArray.class);
            assertThat(result.length()).isEqualTo(values.length);
            for (int i = 0; i < values.length; i++) {
                LongArray longArray = (LongArray) result;
                assertThat(longArray.getLong(i)).isEqualTo(values[i]);
            }
        }
    }
}
