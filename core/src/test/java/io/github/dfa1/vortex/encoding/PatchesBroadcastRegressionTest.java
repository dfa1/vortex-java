package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.proto.DTypeProtos;
import io.github.dfa1.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.proto.ScalarProtos;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

/// Regression for the IOOB crash in `BitpackedEncoding.applyPatches` (and sibling
/// `SparseEncoding`, `AlpEncoding`, `PatchedEncoding`, etc.) when a patches child is
/// encoded with [ConstantEncoding].
///
/// <p>Background: commit `10a7776` made `ConstantEncoding` allocate a single element
/// regardless of declared `rowCount` (zip-bomb defense). Consumers that bulk-read a
/// child segment via raw `MemorySegment.copy(seg, i * elemBytes, ...)` then ran off
/// the end of the 1-element segment as soon as i > 0. The fix broadcasts the lone
/// element across every logical index (see [SegmentBroadcast]).
class PatchesBroadcastRegressionTest {

    @Test
    void bitpackedDecode_withConstantPatchesValues_broadcastsValueAcrossPatches() {
        // Given — hand-build an ArrayNode tree:
        //   bitpacked(bitWidth=1, patches{len=3, U32 indices, I64 values})
        //     ├─ constant U32 indices: {2, 5, 7}  ← multi-element so we exercise idx path
        //     └─ constant I64 values: 42L (1-element seg, must broadcast across 3 patches)
        long n = 10;
        long numPatches = 3;
        long constantPatchValue = 42L;

        // Packed buffer for bitpacked: bitWidth=1, n=10 → ceil(10/8) bytes of zeros.
        // Bitpacked unpacks in 1024-row blocks → buffer size is 1024 * bitWidth / 8 = 128 bytes.
        byte[] packed = new byte[128];

        // Indices constant scalar (single U32 = 2 — all three patches will "see" index 2).
        // The indices being constant is also a broadcast case for the idx side.
        ScalarProtos.ScalarValue idxScalar = ScalarProtos.ScalarValue.newBuilder()
                .setUint64Value(2L)
                .build();
        byte[] idxScalarBytes = idxScalar.toByteArray();

        // Values constant scalar (single I64 = 42).
        ScalarProtos.ScalarValue valScalar = ScalarProtos.ScalarValue.newBuilder()
                .setInt64Value(constantPatchValue)
                .build();
        byte[] valScalarBytes = valScalar.toByteArray();

        // Bitpacked metadata: bitWidth=1, patches{...}.
        EncodingProtos.PatchesMetadata patches = EncodingProtos.PatchesMetadata.newBuilder()
                .setLen(numPatches)
                .setOffset(0)
                .setIndicesPtype(DTypeProtos.PType.U32)
                .build();
        EncodingProtos.BitPackedMetadata meta = EncodingProtos.BitPackedMetadata.newBuilder()
                .setBitWidth(1)
                .setOffset(0)
                .setPatches(patches)
                .build();
        ByteBuffer metaBuf = ByteBuffer.wrap(meta.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);

        // Buffers: [packed, idxConstantScalar, valConstantScalar]
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packedSeg = arena.allocate(packed.length, 8);
            MemorySegment.copy(MemorySegment.ofArray(packed), 0, packedSeg, 0, packed.length);
            MemorySegment idxBufSeg = arena.allocate(idxScalarBytes.length, 1);
            MemorySegment.copy(MemorySegment.ofArray(idxScalarBytes), 0, idxBufSeg, 0, idxScalarBytes.length);
            MemorySegment valBufSeg = arena.allocate(valScalarBytes.length, 1);
            MemorySegment.copy(MemorySegment.ofArray(valScalarBytes), 0, valBufSeg, 0, valScalarBytes.length);

            ArrayNode idxChild = ArrayNode.of(EncodingId.VORTEX_CONSTANT, null,
                    new ArrayNode[0], new int[]{1}, null);
            ArrayNode valChild = ArrayNode.of(EncodingId.VORTEX_CONSTANT, null,
                    new ArrayNode[0], new int[]{2}, null);
            ArrayNode root = ArrayNode.of(EncodingId.FASTLANES_BITPACKED, metaBuf,
                    new ArrayNode[]{idxChild, valChild}, new int[]{0}, null);

            DType dtype = new DType.Primitive(PType.I64, false);
            Registry registry = Registry.loadAll();
            DecodeContext ctx = new DecodeContext(root, dtype, n,
                    new MemorySegment[]{packedSeg, idxBufSeg, valBufSeg},
                    registry, Arena.ofAuto());

            // When
            Array result = new BitpackedEncoding().decode(ctx);

            // Then
            // All 3 patches collapse onto logical index 2 (constant indices) and write 42.
            // Non-patched positions remain 0 (bitWidth=1, packed=zeros). Index 2 is the only
            // patch target — it must hold the broadcasted value 42, not throw IOOB.
            assertThat(result.length()).isEqualTo(n);
            MemorySegment data = ArraySegments.of(result);
            assertThat(data.getAtIndex(PTypeIO.LE_LONG, 2)).isEqualTo(constantPatchValue);
            for (long i = 0; i < n; i++) {
                if (i == 2) {
                    continue;
                }
                assertThat(data.getAtIndex(PTypeIO.LE_LONG, i)).as("non-patched index %d", i).isZero();
            }
        }
    }

    @Test
    void segmentBroadcast_elementOffset_wrapsConstantSegment() {
        // Given — a 1-element segment (the ConstantEncoding shape).
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            seg.set(PTypeIO.LE_LONG, 0, 99L);

            // When + Then — every logical index reads from offset 0.
            assertThat(SegmentBroadcast.elementOffset(seg, 0, 8)).isZero();
            assertThat(SegmentBroadcast.elementOffset(seg, 5, 8)).isZero();
            assertThat(SegmentBroadcast.elementOffset(seg, 999_999, 8)).isZero();
            assertThat(SegmentBroadcast.capacity(seg, 8)).isEqualTo(1L);
        }
    }

    @Test
    void segmentBroadcast_elementOffset_passesThroughFullSegment() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8 * 4);
            // When + Then — fully-materialized segment: offsets pass through unchanged.
            for (long i = 0; i < 4; i++) {
                assertThat(SegmentBroadcast.elementOffset(seg, i, 8)).isEqualTo(i * 8);
            }
            // Out-of-bounds logical index still wraps (caller's responsibility to bound i).
            assertThat(SegmentBroadcast.elementOffset(seg, 4, 8)).isZero();
            assertThat(SegmentBroadcast.capacity(seg, 8)).isEqualTo(4L);
        }
    }

    @Test
    void segmentBroadcast_broadcastCopy_replicatesSingleElement() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(8);
            src.set(PTypeIO.LE_LONG, 0, 0xCAFEBABEL);
            MemorySegment dst = arena.allocate(8 * 5);

            // When
            SegmentBroadcast.broadcastCopy(src, dst, 5, 8);

            // Then
            for (long i = 0; i < 5; i++) {
                assertThat(dst.getAtIndex(PTypeIO.LE_LONG, i)).isEqualTo(0xCAFEBABEL);
            }
        }
    }
}
