package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoBitPackedMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import static org.assertj.core.api.Assertions.assertThat;

class FrameOfReferenceEncodingDecoderTest {

    private static final FrameOfReferenceEncodingDecoder SUT = new FrameOfReferenceEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new BitpackedEncodingDecoder(), new PrimitiveEncodingDecoder());
    private static final DType I32 = new DType.Primitive(PType.I32, false);
    private static final MemorySegment REF_5 = MemorySegment.ofArray(ProtoScalarValue.ofInt64Value(5L).encode());

    @Test
    void decode_bitpackedChild_addsReferenceInPlaceIntoFlatArray() {
        // Given FoR(ref 5) over bitpacked width 0 (every unpacked value 0): the unpacked buffer is
        // owned by this decode, so the reference is added there and the column comes back flat
        byte[] bitpackedMeta = new ProtoBitPackedMetadata(0, 0, null).encode();
        ArrayNode bitpacked = new ArrayNode(EncodingId.FASTLANES_BITPACKED, MemorySegment.ofArray(bitpackedMeta),
                new ArrayNode[0], new int[]{0});
        ArrayNode node = new ArrayNode(EncodingId.FASTLANES_FOR, REF_5, new ArrayNode[]{bitpacked}, new int[0]);
        var ctx = new DecodeContext(node, I32, 3, new MemorySegment[]{MemorySegment.ofArray(new byte[0])},
                REGISTRY, Arena.ofAuto());

        // When
        Array result = SUT.decode(ctx);

        // Then
        assertThat(result.segmentIfPresent()).isPresent();
        IntArray values = (IntArray) result;
        assertThat(new int[]{values.getInt(0), values.getInt(1), values.getInt(2)}).containsExactly(5, 5, 5);
    }

    @Test
    void decode_primitiveChild_leavesChildBufferUntouched() {
        // Given FoR over a plain primitive child: in a real file that buffer is a zero-copy slice of
        // the memory-mapped file, so the reference must never be added into it
        MemorySegment raw = TestSegments.leInts(1, 2, 3);
        ArrayNode primitive = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode node = new ArrayNode(EncodingId.FASTLANES_FOR, REF_5, new ArrayNode[]{primitive}, new int[0]);
        var ctx = new DecodeContext(node, I32, 3, new MemorySegment[]{raw}, REGISTRY, Arena.ofAuto());

        // When
        IntArray result = (IntArray) SUT.decode(ctx);

        // Then
        assertThat(new int[]{result.getInt(0), result.getInt(1), result.getInt(2)}).containsExactly(6, 7, 8);
        assertThat(new int[]{raw.getAtIndex(LE_INT, 0), raw.getAtIndex(LE_INT, 1), raw.getAtIndex(LE_INT, 2)})
                .containsExactly(1, 2, 3);
    }
}
