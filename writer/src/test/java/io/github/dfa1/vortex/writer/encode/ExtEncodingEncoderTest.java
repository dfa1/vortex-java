package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.CascadeStep;
import io.github.dfa1.vortex.encoding.ChildSlot;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.encoding.EncodeNode;
import io.github.dfa1.vortex.encoding.EncodeResult;
import io.github.dfa1.vortex.encoding.EncodeTestHelper;
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
            DType extDType = new DType.Extension("vortex.timestamp",
                    new DType.Primitive(PType.I64, false), null, false);
            assertThat(ENCODER.accepts(extDType)).isTrue();
        }

        @Test
        void accepts_primitiveDtype_returnsFalse() {
            assertThat(ENCODER.accepts(new DType.Primitive(PType.I64, false))).isFalse();
        }

        @Test
        void encode_extensionWrappingI64_roundTrips() {
            long[] data = {100L, 200L, 300L, 400L};
            DType storageDType = new DType.Primitive(PType.I64, false);
            DType extDType = new DType.Extension("vortex.timestamp", storageDType, null, false);

            EncodeResult result = ENCODER.encode(extDType, data, EncodeTestHelper.testCtx());

            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_EXT);
            assertThat(result.rootNode().children()).hasSize(1);
            assertThat(result.rootNode().children()[0].encodingId()).isEqualTo(EncodingId.VORTEX_PRIMITIVE);

            ArrayNode rootNode = encodeNodeToArrayNode(result.rootNode());
            DecodeContext ctx = new DecodeContext(
                    rootNode, extDType, data.length,
                    result.buffers().toArray(MemorySegment[]::new),
                    REGISTRY, Arena.ofAuto());
            var decoded = DECODER.decode(ctx);

            assertThat(decoded).isInstanceOf(LongArray.class);
            LongArray longArray = (LongArray) decoded;
            for (int i = 0; i < data.length; i++) {
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
            long[] data = {100L, 200L, 300L, 400L};
            DType storageDType = new DType.Primitive(PType.I64, false);
            DType extDType = new DType.Extension("vortex.timestamp", storageDType, null, false);

            CascadeStep step = ENCODER.encodeCascade(extDType, data, EncodeTestHelper.testCtx());

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
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    ENCODER.encodeCascade(new DType.Primitive(PType.I64, false), new long[]{1L},
                            EncodeTestHelper.testCtx()))
                    .isInstanceOf(io.github.dfa1.vortex.core.VortexException.class)
                    .hasMessageContaining("expected extension dtype");
        }
    }

    @Nested
    class Decode {

        @Test
        void decode_extensionWrappingI64_returnsStorageArray() {
            long[] values = {10L, 20L, 30L, 40L};
            MemorySegment buf = TestSegments.leLongs(values);

            DType storageDType = new DType.Primitive(PType.I64, false);
            DType extDType = new DType.Extension("vortex.timestamp", storageDType, null, false);

            ArrayNode primitiveNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0}, null);
            ArrayNode extNode = ArrayNode.of(EncodingId.VORTEX_EXT, null, new ArrayNode[]{primitiveNode}, new int[0], null);

            DecodeContext ctx = new DecodeContext(
                    extNode, extDType, values.length, new MemorySegment[]{buf}, REGISTRY, Arena.ofAuto());

            var result = DECODER.decode(ctx);

            assertThat(result).isInstanceOf(LongArray.class);
            assertThat(result.length()).isEqualTo(values.length);
            LongArray longArray = (LongArray) result;
            for (int i = 0; i < values.length; i++) {
                assertThat(longArray.getLong(i)).isEqualTo(values[i]);
            }
        }
    }
}
