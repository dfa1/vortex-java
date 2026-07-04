package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.encoding.TestSegments;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class VarBinEncodingDecoderTest {

    private static final VarBinEncodingDecoder SUT = new VarBinEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(SUT, new PrimitiveEncodingDecoder());

    private static MemorySegment i32OffsetsMeta() {
        return MemorySegment.ofArray(new ProtoVarBinMetadata(io.github.dfa1.vortex.core.proto.ProtoPType.I32).encode());
    }

    private static DecodeContext ctx(MemorySegment meta, MemorySegment bytes, MemorySegment offsets, long n) {
        // children[0] = offsets (primitive, segment index 1); bufferIndices[0] -> bytes (index 0)
        ArrayNode offsetsNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode varbinNode = new ArrayNode(EncodingId.VORTEX_VARBIN, meta, new ArrayNode[]{offsetsNode}, new int[]{0});
        return new DecodeContext(varbinNode, DType.UTF8, n,
                new MemorySegment[]{bytes, offsets}, REGISTRY, Arena.ofAuto());
    }

    @Test
    void decode_i32Offsets_happyPath() {
        // Given "a","b","c" with I32 offsets (the encoder defaults to I64, so this
        // exercises the I32 offsets-ptype branch directly)
        MemorySegment data = MemorySegment.ofArray("abc".getBytes(StandardCharsets.UTF_8));
        MemorySegment offsets = TestSegments.leInts(0, 1, 2, 3);

        // When
        Array result = SUT.decode(ctx(i32OffsetsMeta(), data, offsets, 3));

        // Then
        VarBinArray arr = (VarBinArray) result;
        assertThat(arr.length()).isEqualTo(3);
        assertThat(arr.getBytes(0)).containsExactly('a');
        assertThat(arr.getBytes(1)).containsExactly('b');
        assertThat(arr.getBytes(2)).containsExactly('c');
    }

    @Test
    void decode_broadcastOffsets_singleOffsetExpandsToAllRows() {
        // Given an offsets child holding a single value (as ConstantEncoding emits):
        // capacity 1 < n+1, so the decoder must broadcast-copy it. A constant offset
        // means every row spans an empty slice.
        MemorySegment data = Arena.ofAuto().allocate(1);
        MemorySegment offsets = TestSegments.leInts(0); // one element only

        // When
        Array result = SUT.decode(ctx(i32OffsetsMeta(), data, offsets, 3));

        // Then
        VarBinArray arr = (VarBinArray) result;
        assertThat(arr.length()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(arr.getBytes(i)).as("index %d", i).isEmpty();
        }
    }
}
