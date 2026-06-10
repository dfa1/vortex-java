package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.proto.BitPackedMetadata;
import io.github.dfa1.vortex.proto.PatchesMetadata;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class BitpackedEncodingPatchesTest {

    @Nested
    class Decode {

        @Test
        void decode_appliesPatches_overridingBitPackedValues() {
            // Given — bit-pack [10, 20, 30, 40, 50] via the production encoder (bitWidth = 6),
            // then attach synthetic patches metadata that rewrites indices [1, 3] with [777, 999].
            int[] base = {10, 20, 30, 40, 50};
            BitpackedEncoding sut = new BitpackedEncoding();
            EncodeResult packed = sut.encode(DTypes.I32, base, EncodeTestHelper.testCtx());

            MemorySegment packedSeg = packed.buffers().getFirst();
            byte[] packedBytes = packedSeg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);

            // Build new BitPackedMetadata that re-uses the packed bytes but advertises patches.
            PatchesMetadata patches = new PatchesMetadata(2L, 0L,
                    io.github.dfa1.vortex.proto.PType.U32, null, null, null);
            byte[] metaBytes = new BitPackedMetadata(6, 0, patches).encode();

            byte[] idxBuf = new byte[2 * 4];
            ByteBuffer.wrap(idxBuf).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(3);
            byte[] valBuf = new byte[2 * 4];
            ByteBuffer.wrap(valBuf).order(ByteOrder.LITTLE_ENDIAN).putInt(777).putInt(999);

            ArrayNode idxNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{1}, ArrayStats.empty());
            ArrayNode valNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{2}, ArrayStats.empty());
            ArrayNode bpNode = ArrayNode.of(EncodingId.FASTLANES_BITPACKED,
                    ByteBuffer.wrap(metaBytes),
                    new ArrayNode[]{idxNode, valNode},
                    new int[]{0},
                    ArrayStats.empty());

            MemorySegment[] segments = {
                    MemorySegment.ofArray(packedBytes),
                    MemorySegment.ofArray(idxBuf),
                    MemorySegment.ofArray(valBuf)
            };

            Registry registry = TestRegistry.of(new BitpackedEncoding(), new PrimitiveEncoding());

            DecodeContext ctx = DecodeContext.ofRawBuffers(
                    bpNode, DTypes.I32, base.length, segments, registry, Arena.global());

            // When
            Array result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(base.length);
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, 0L)).isEqualTo(10);
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, 4L)).isEqualTo(777);
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, 8L)).isEqualTo(30);
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, 12L)).isEqualTo(999);
            assertThat(ArraySegments.of(result).get(PTypeIO.LE_INT, 16L)).isEqualTo(50);
        }
    }
}
