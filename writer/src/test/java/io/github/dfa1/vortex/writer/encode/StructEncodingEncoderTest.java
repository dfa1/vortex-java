package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.encoding.TestSegments;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.StructEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructEncodingEncoderTest {

    private static final StructEncodingEncoder ENCODER = new StructEncodingEncoder();
    private static final StructEncodingDecoder DECODER = new StructEncodingDecoder();
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
        void accepts_structDtype_trueForStruct_falseForPrimitive() {
            // Given
            DType.Struct structDtype = new DType.Struct(List.of("x"), List.of(DTypes.I64), false);

            // When
            // Then
            assertThat(ENCODER.accepts(structDtype)).isTrue();
            assertThat(ENCODER.accepts(DTypes.I64)).isFalse();
        }

        @Test
        void roundTrip_twoI64Fields_preservesValues() {
            // Given
            long[] ids = {1L, 2L, 3L};
            long[] values = {10L, 20L, 30L};
            DType.Struct dtype = new DType.Struct(
                    List.of("id", "value"), List.of(DTypes.I64, DTypes.I64), false);
            StructData data = new StructData(List.of(ids, values));

            // When
            EncodeResult result = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), dtype, ids.length, bufs, REGISTRY, Arena.global());
            StructArray decoded = (StructArray) DECODER.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(ids.length);
            assertThat(decoded.fieldCount()).isEqualTo(2);
            LongArray idField = (LongArray) decoded.field(0);
            LongArray valueField = (LongArray) decoded.field(1);
            for (int i = 0; i < ids.length; i++) {
                assertThat(idField.getLong(i)).isEqualTo(ids[i]);
                assertThat(valueField.getLong(i)).isEqualTo(values[i]);
            }
        }

        @Test
        void singleField_encodeResult_hasOneChildAndNoBuffers() {
            // Given
            long[] data = {7L, 14L, 21L};
            DType.Struct dtype = new DType.Struct(List.of("v"), List.of(DTypes.I64), false);

            // When
            EncodeResult result = ENCODER.encode(dtype, new StructData(List.of(data)), EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_STRUCT);
            assertThat(result.rootNode().children()).hasSize(1);
            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.buffers()).hasSize(1);
        }

        @Test
        void fieldCountMismatch_throwsVortexException() {
            // Given
            DType.Struct dtype = new DType.Struct(List.of("a", "b"), List.of(DTypes.I64, DTypes.I64), false);
            StructData data = new StructData(List.of(new long[]{1L}));

            // When
            // Then
            org.junit.jupiter.api.Assertions.assertThrows(
                    io.github.dfa1.vortex.core.VortexException.class,
                    () -> ENCODER.encode(dtype, data, EncodeTestHelper.testCtx()));
        }
    }

    @Nested
    class Decode {

        private static ArrayNode primitiveNode(int bufferIdx) {
            return ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0],
                    new int[]{bufferIdx});
        }

        private static ArrayNode boolNode(int bufferIdx) {
            return ArrayNode.of(EncodingId.VORTEX_BOOL, null, new ArrayNode[0],
                    new int[]{bufferIdx});
        }

        @Test
        void decode_nonNullableWrapper_oneChild_returnsValues() {
            // Given
            long[] data = {10L, 20L, 30L};
            MemorySegment seg = TestSegments.leLongs(data);
            ArrayNode valuesNode = primitiveNode(0);
            ArrayNode structNode = ArrayNode.of(EncodingId.VORTEX_STRUCT, null,
                    new ArrayNode[]{valuesNode}, new int[0]);
            DecodeContext ctx = new DecodeContext(structNode, DTypes.I64, data.length,
                    new MemorySegment[]{seg}, REGISTRY, Arena.global());

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(data.length);
            for (int i = 0; i < data.length; i++) {
                assertThat(ArraySegments.of(result).get(PTypeIO.LE_LONG, (long) i * 8)).isEqualTo(data[i]);
            }
        }

        @Test
        void decode_nullableWrapper_twoChildren_returnsMaskedArray() {
            // Given
            long[] data = {7L, 14L, 21L};
            MemorySegment validitySeg = MemorySegment.ofArray(new byte[]{(byte) 0xFF});
            MemorySegment valuesSeg = TestSegments.leLongs(data);

            ArrayNode validityNode = boolNode(0);
            ArrayNode valuesNode = primitiveNode(1);
            ArrayNode structNode = ArrayNode.of(EncodingId.VORTEX_STRUCT, null,
                    new ArrayNode[]{validityNode, valuesNode}, new int[0]);

            ReadRegistry registry = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());
            DecodeContext ctx = new DecodeContext(
                    structNode, DTypes.I64, data.length,
                    new MemorySegment[]{validitySeg, valuesSeg},
                    registry, Arena.global());

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result).isInstanceOf(MaskedArray.class);
            MaskedArray masked = (MaskedArray) result;
            LongArray values = (LongArray) masked.inner();
            assertThat(values.length()).isEqualTo(data.length);
            for (int i = 0; i < data.length; i++) {
                assertThat(masked.isValid(i)).isTrue();
                assertThat(values.getLong(i)).isEqualTo(data[i]);
            }
        }
    }
}
