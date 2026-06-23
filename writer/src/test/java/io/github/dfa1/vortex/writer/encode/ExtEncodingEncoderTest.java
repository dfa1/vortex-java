package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.encoding.TestSegments;
import io.github.dfa1.vortex.reader.decode.ExtEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

class ExtEncodingEncoderTest {

    private static final ExtEncodingEncoder ENCODER = new ExtEncodingEncoder();
    private static final ExtEncodingDecoder DECODER = new ExtEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(new PrimitiveEncodingDecoder(), DECODER);

    @Nested
    class Encode {

        @Test
        void accepts_extensionDtype_returnsTrue() {
            // Given
            DType extDType = new DType.Extension("vortex.timestamp",
                    DType.I64, null, false);

            // When / Then
            assertThat(ENCODER.accepts(extDType)).isTrue();
        }

        @Test
        void accepts_primitiveDtype_returnsFalse() {
            // Given / When / Then
            assertThat(ENCODER.accepts(DType.I64)).isFalse();
        }

        @Test
        void encode_extensionWrappingI64_roundTrips() {
            // Given
            long[] data = {100L, 200L, 300L, 400L};
            DType storageDType = DType.I64;
            DType extDType = new DType.Extension("vortex.timestamp", storageDType, null, false);

            // When
            EncodeResult resultEncoded = ENCODER.encode(extDType, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(resultEncoded.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_EXT);
            assertThat(resultEncoded.rootNode().children()).hasSize(1);
            assertThat(resultEncoded.rootNode().children()[0].encodingId()).isEqualTo(EncodingId.VORTEX_PRIMITIVE);

            ArrayNode rootNode = encodeNodeToArrayNode(resultEncoded.rootNode());
            DecodeContext ctx = new DecodeContext(
                    rootNode, extDType, data.length,
                    resultEncoded.buffers().toArray(MemorySegment[]::new),
                    REGISTRY, Arena.ofAuto());
            var resultDecoded = DECODER.decode(ctx);

            assertThat(resultDecoded).isInstanceOf(LongArray.class);
            LongArray longArray = (LongArray) resultDecoded;
            for (int i = 0; i < data.length; i++) {
                assertThat(longArray.getLong(i)).isEqualTo(data[i]);
            }
        }

        private ArrayNode encodeNodeToArrayNode(EncodeNode n) {
            ArrayNode[] children = new ArrayNode[n.children().length];
            for (int i = 0; i < children.length; i++) {
                children[i] = encodeNodeToArrayNode(n.children()[i]);
            }
            return ArrayNode.of(n.encodingId(), n.metadata(), children, n.bufferIndices());
        }
    }

    @Nested
    class Cascade {

        @Test
        void encodeCascade_exposesStorageAsOpenChild() {
            // Given
            long[] data = {100L, 200L, 300L, 400L};
            DType storageDType = DType.I64;
            DType extDType = new DType.Extension("vortex.timestamp", storageDType, null, false);

            // When
            CascadeStep result = ENCODER.encodeCascade(extDType, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.applicable()).isTrue();
            assertThat(result.isTerminal()).isFalse();
            assertThat(result.openChildren()).hasSize(1);
            ChildSlot slot = result.openChildren().get(0);
            assertThat(slot.childDtype()).isEqualTo(storageDType);
            assertThat(slot.childData()).isSameAs(data);
            assertThat(slot.parentChildIdx()).isZero();
            assertThat(result.partialRoot().encodingId()).isEqualTo(EncodingId.VORTEX_EXT);
            assertThat(result.partialRoot().children()).hasSize(1);
        }

        @Test
        void encodeCascade_rejectsNonExtensionDtype() {
            // Given / When / Then
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    ENCODER.encodeCascade(DType.I64, new long[]{1L},
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

            DType storageDType = DType.I64;
            DType extDType = new DType.Extension("vortex.timestamp", storageDType, null, false);

            ArrayNode primitiveNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
            ArrayNode extNode = ArrayNode.of(EncodingId.VORTEX_EXT, null, new ArrayNode[]{primitiveNode}, new int[0]);

            DecodeContext ctx = new DecodeContext(
                    extNode, extDType, values.length, new MemorySegment[]{buf}, REGISTRY, Arena.ofAuto());

            // When
            var result = DECODER.decode(ctx);

            // Then
            assertThat(result).isInstanceOf(LongArray.class);
            assertThat(result.length()).isEqualTo(values.length);
            LongArray longArray = (LongArray) result;
            for (int i = 0; i < values.length; i++) {
                assertThat(longArray.getLong(i)).isEqualTo(values[i]);
            }
        }
    }
}
