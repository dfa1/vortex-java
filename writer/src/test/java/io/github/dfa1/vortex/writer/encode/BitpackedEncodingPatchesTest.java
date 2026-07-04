package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoBitPackedMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.reader.decode.BitpackedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class BitpackedEncodingPatchesTest {

    private static final BitpackedEncodingEncoder ENCODER = new BitpackedEncodingEncoder();
    private static final BitpackedEncodingDecoder DECODER = new BitpackedEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    @Nested
    class Decode {

        @Test
        void decode_appliesPatches_overridingBitPackedValues() {
            // Given
            int[] base = {10, 20, 30, 40, 50};
            EncodeResult packed = ENCODER.encode(DTypes.I32, base, EncodeTestHelper.testCtx());

            MemorySegment packedSeg = packed.buffers().getFirst();
            byte[] packedBytes = packedSeg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);

            ProtoPatchesMetadata patches = new ProtoPatchesMetadata(2L, 0L,
                    io.github.dfa1.vortex.core.proto.ProtoPType.U32, null, null, null);
            byte[] metaBytes = new ProtoBitPackedMetadata(6, 0, patches).encode();

            byte[] idxBuf = new byte[2 * 4];
            ByteBuffer.wrap(idxBuf).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(3);
            byte[] valBuf = new byte[2 * 4];
            ByteBuffer.wrap(valBuf).order(ByteOrder.LITTLE_ENDIAN).putInt(777).putInt(999);

            ArrayNode idxNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{1});
            ArrayNode valNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{2});
            ArrayNode bpNode = new ArrayNode(EncodingId.FASTLANES_BITPACKED,
                    MemorySegment.ofArray(metaBytes),
                    new ArrayNode[]{idxNode, valNode},
                    new int[]{0});

            MemorySegment[] segments = {
                    MemorySegment.ofArray(packedBytes),
                    MemorySegment.ofArray(idxBuf),
                    MemorySegment.ofArray(valBuf)
            };

            DecodeContext ctx = new DecodeContext(
                    bpNode, DTypes.I32, base.length, segments, REGISTRY, Arena.global());

            // When
            Array result = DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(base.length);
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 0L)).isEqualTo(10);
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 4L)).isEqualTo(777);
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 8L)).isEqualTo(30);
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 12L)).isEqualTo(999);
            assertThat(result.materialize(Arena.ofAuto()).get(VortexFormat.LE_INT, 16L)).isEqualTo(50);
        }
    }

    @Nested
    class Encode {

        @Test
        void encode_thenDecode_roundTripsWithPatches() {
            // Given
            // U8 codes mostly zero with one outlier — encoder picks bit_width=0 + 1 patch.
            byte[] codes = new byte[1000];
            codes[42] = 7;

            // When
            EncodeResult result = ENCODER.encode(DTypes.U8, codes, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.buffers()).hasSize(3); // packed (0 B) + idx + val
            assertThat(result.rootNode().children()).hasSize(2);

            MemorySegment[] segments = {
                    result.buffers().get(0),
                    result.buffers().get(1),
                    result.buffers().get(2)
            };
            ArrayNode idxNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{1});
            ArrayNode valNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                    new ArrayNode[0], new int[]{2});
            ArrayNode bpNode = new ArrayNode(EncodingId.FASTLANES_BITPACKED,
                    result.rootNode().metadata(),
                    new ArrayNode[]{idxNode, valNode},
                    new int[]{0});

            DecodeContext ctx = new DecodeContext(
                    bpNode, DTypes.U8, codes.length, segments, REGISTRY, Arena.global());
            Array decoded = DECODER.decode(ctx);

            assertThat(decoded.length()).isEqualTo(codes.length);
            for (int i = 0; i < codes.length; i++) {
                byte got = decoded.materialize(Arena.ofAuto()).get(java.lang.foreign.ValueLayout.JAVA_BYTE, i);
                assertThat(got).as("idx " + i).isEqualTo(codes[i]);
            }
        }
    }
}
