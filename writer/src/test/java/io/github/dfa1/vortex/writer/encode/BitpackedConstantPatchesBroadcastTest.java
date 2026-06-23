package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.proto.BitPackedMetadata;
import io.github.dfa1.vortex.proto.PatchesMetadata;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.reader.decode.BitpackedEncodingDecoder;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

/// Regression for the IOOB crash in `BitpackedEncoding.applyPatches` (and sibling
/// `SparseEncoding`, `AlpEncoding`, `PatchedEncoding`, etc.) when a patches child is
/// encoded with [io.github.dfa1.vortex.encoding.ConstantEncoding].
class BitpackedConstantPatchesBroadcastTest {

    @Test
    void bitpackedDecode_withConstantPatchesValues_broadcastsValueAcrossPatches() {
        // Given
        long n = 10;
        long numPatches = 3;
        long constantPatchValue = 42L;

        byte[] packed = new byte[128];

        ScalarValue idxScalar = ScalarValue.ofUint64Value(2L);
        byte[] idxScalarBytes = idxScalar.encode();

        ScalarValue valScalar = ScalarValue.ofInt64Value(constantPatchValue);
        byte[] valScalarBytes = valScalar.encode();

        PatchesMetadata patches = new PatchesMetadata(numPatches, 0,
                io.github.dfa1.vortex.proto.PType.U32, null, null, null);
        BitPackedMetadata meta = new BitPackedMetadata(1, 0, patches);
        ByteBuffer metaBuf = ByteBuffer.wrap(meta.encode()).order(ByteOrder.LITTLE_ENDIAN);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packedSeg = arena.allocate(packed.length, 8);
            MemorySegment.copy(MemorySegment.ofArray(packed), 0, packedSeg, 0, packed.length);
            MemorySegment idxBufSeg = arena.allocate(idxScalarBytes.length, 1);
            MemorySegment.copy(MemorySegment.ofArray(idxScalarBytes), 0, idxBufSeg, 0, idxScalarBytes.length);
            MemorySegment valBufSeg = arena.allocate(valScalarBytes.length, 1);
            MemorySegment.copy(MemorySegment.ofArray(valScalarBytes), 0, valBufSeg, 0, valScalarBytes.length);

            ArrayNode idxChild = ArrayNode.of(EncodingId.VORTEX_CONSTANT, null,
                    new ArrayNode[0], new int[]{1});
            ArrayNode valChild = ArrayNode.of(EncodingId.VORTEX_CONSTANT, null,
                    new ArrayNode[0], new int[]{2});
            ArrayNode root = ArrayNode.of(EncodingId.FASTLANES_BITPACKED, metaBuf,
                    new ArrayNode[]{idxChild, valChild}, new int[]{0});

            DType dtype = DType.I64;
            ReadRegistry registry = ReadRegistry.loadAll();
            DecodeContext ctx = new DecodeContext(root, dtype, n,
                    new MemorySegment[]{packedSeg, idxBufSeg, valBufSeg},
                    registry, Arena.ofAuto());

            // When
            Array result = new BitpackedEncodingDecoder().decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(n);
            MemorySegment data = result.materialize(Arena.ofAuto());
            assertThat(data.getAtIndex(PTypeIO.LE_LONG, 2)).isEqualTo(constantPatchValue);
            for (long i = 0; i < n; i++) {
                if (i == 2) {
                    continue;
                }
                assertThat(data.getAtIndex(PTypeIO.LE_LONG, i)).as("non-patched index %d", i).isZero();
            }
        }
    }
}
