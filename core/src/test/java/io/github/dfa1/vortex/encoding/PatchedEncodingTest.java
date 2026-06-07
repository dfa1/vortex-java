package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchedEncodingTest {

    // Build metadata bytes for PatchedMetadata { n_patches=field1, n_lanes=field2, offset=field3 }
    // Proto3 varint encoding: tag = (fieldNumber << 3) | wireType(0)
    private static ByteBuffer patchedMeta(int nPatches, int nLanes, int offset) {
        byte[] buf = new byte[12];
        int pos = 0;
        // field 1: n_patches
        buf[pos++] = 0x08;
        pos = writeVarint(buf, pos, nPatches);
        // field 2: n_lanes
        buf[pos++] = 0x10;
        pos = writeVarint(buf, pos, nLanes);
        // field 3: offset (skip if 0 — proto3 omits default values)
        if (offset != 0) {
            buf[pos++] = 0x18;
            pos = writeVarint(buf, pos, offset);
        }
        return ByteBuffer.wrap(buf, 0, pos);
    }

    private static int writeVarint(byte[] buf, int pos, int value) {
        while ((value & ~0x7F) != 0) {
            buf[pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf[pos++] = (byte) value;
        return pos;
    }

    private static MemorySegment i32Segment(int... values) {
        MemorySegment seg = MemorySegment.ofArray(new byte[values.length * 4]);
        ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) {
            bb.putInt(v);
        }
        return seg;
    }

    private static MemorySegment u32Segment(int... values) {
        return i32Segment(values);
    }

    private static MemorySegment u16Segment(short... values) {
        MemorySegment seg = MemorySegment.ofArray(new byte[values.length * 2]);
        ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        for (short v : values) {
            bb.putShort(v);
        }
        return seg;
    }

    private static MemorySegment i64Segment(long... values) {
        MemorySegment seg = MemorySegment.ofArray(new byte[values.length * 8]);
        ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        for (long v : values) {
            bb.putLong(v);
        }
        return seg;
    }

    // Decode a patched primitive array given the raw constituent segments.
    // n_lanes=1, offset=0 unless overridden.
    private static Array decode(
            int n, int[] innerI32, int[] laneOffsets, short[] patchIndices, int[] patchValues
    ) {
        return decode(new DType.Primitive(PType.I32, false), n,
                i32Segment(innerI32), u32Segment(laneOffsets),
                u16Segment(patchIndices), i32Segment(patchValues),
                laneOffsets.length - 1);
    }

    private static Array decode(
            DType dtype, int n,
            MemorySegment inner, MemorySegment laneOffsets,
            MemorySegment patchIndices, MemorySegment patchValues,
            int nLanes
    ) {
        int nPatches = (int) (patchIndices.byteSize() / 2);
        int nChunks = (int) ((n + 1023) / 1024);
        ByteBuffer meta = patchedMeta(nPatches, nLanes, 0);

        MemorySegment[] segments = {inner, laneOffsets, patchIndices, patchValues};

        ArrayNode innerNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0}, null);
        ArrayNode laneNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1}, null);
        ArrayNode idxNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{2}, null);
        ArrayNode valNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{3}, null);

        ArrayNode patchedNode = ArrayNode.of(EncodingId.VORTEX_PATCHED, meta,
                new ArrayNode[]{innerNode, laneNode, idxNode, valNode}, new int[]{}, null);

        EncodingRegistry registry = TestRegistry.of(new PatchedEncoding(), new PrimitiveEncoding());
        DecodeContext ctx = new DecodeContext(patchedNode, dtype, n, segments, registry, Arena.ofAuto());
        return new PatchedEncoding().decode(ctx);
    }

    @Nested
    class Decode {

        @Test
        void decode_noPatches_returnsInnerUnchanged() {
            // Given — inner=[10, 20, 30, 40], no patches
            int n = 4;
            int[] inner = {10, 20, 30, 40};
            int[] laneOffsets = {0, 0};
            short[] patchIndices = {};
            int[] patchValues = {};

            // When
            Array sut = decode(n, inner, laneOffsets, patchIndices, patchValues);

            // Then — output matches inner exactly
            assertThat(sut).isInstanceOf(IntArray.class);
            MemorySegment seg = ArraySegments.of(sut);
            for (int i = 0; i < n; i++) {
                assertThat(seg.getAtIndex(PTypeIO.LE_INT, i)).as("index %d", i).isEqualTo(inner[i]);
            }
        }

        @Test
        void decode_singlePatch_overwrites() {
            // Given — inner=[10, 20, 30, 40], patch index=2 → value=99
            int n = 4;
            int[] inner = {10, 20, 30, 40};
            int[] laneOffsets = {0, 1};
            short[] patchIndices = {2};
            int[] patchValues = {99};

            // When
            Array sut = decode(n, inner, laneOffsets, patchIndices, patchValues);

            // Then — only index 2 changed
            MemorySegment seg = ArraySegments.of(sut);
            assertThat(seg.getAtIndex(PTypeIO.LE_INT, 0)).isEqualTo(10);
            assertThat(seg.getAtIndex(PTypeIO.LE_INT, 1)).isEqualTo(20);
            assertThat(seg.getAtIndex(PTypeIO.LE_INT, 2)).isEqualTo(99);
            assertThat(seg.getAtIndex(PTypeIO.LE_INT, 3)).isEqualTo(40);
        }

        @Test
        void decode_multiplePatches_allApplied() {
            // Given — inner=[0]*4, patches: idx=0→1, idx=3→7
            int n = 4;
            int[] inner = {0, 0, 0, 0};
            int[] laneOffsets = {0, 2};
            short[] patchIndices = {0, 3};
            int[] patchValues = {1, 7};

            // When
            Array sut = decode(n, inner, laneOffsets, patchIndices, patchValues);

            // Then
            MemorySegment seg = ArraySegments.of(sut);
            assertThat(seg.getAtIndex(PTypeIO.LE_INT, 0)).isEqualTo(1);
            assertThat(seg.getAtIndex(PTypeIO.LE_INT, 1)).isEqualTo(0);
            assertThat(seg.getAtIndex(PTypeIO.LE_INT, 2)).isEqualTo(0);
            assertThat(seg.getAtIndex(PTypeIO.LE_INT, 3)).isEqualTo(7);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 1020, 1023, 1024, 1025, 2048})
        void decode_variousLengths_noPatches(int n) {
            // Given — all-zeros inner, no patches; lane_offsets has 2 entries [0, 0]
            int[] inner = new int[n];
            int[] laneOffsets = {0, 0};
            short[] patchIndices = {};
            int[] patchValues = {};

            // When
            Array sut = decode(n, inner, laneOffsets, patchIndices, patchValues);

            // Then — all zeros
            MemorySegment seg = ArraySegments.of(sut);
            for (int i = 0; i < n; i++) {
                assertThat(seg.getAtIndex(PTypeIO.LE_INT, i)).as("index %d", i).isZero();
            }
        }

        @Test
        void decode_i64_singlePatch() {
            // Given — inner (I64) =[100L, 200L, 300L], patch idx=1 → 999L
            int n = 3;
            DType dtype = new DType.Primitive(PType.I64, false);
            MemorySegment inner = i64Segment(100L, 200L, 300L);
            MemorySegment laneOffsets = u32Segment(0, 1);
            MemorySegment patchIndices = u16Segment((short) 1);
            MemorySegment patchValues = i64Segment(999L);

            // When
            Array sut = decode(dtype, n, inner, laneOffsets, patchIndices, patchValues, 1);

            // Then
            assertThat(sut).isInstanceOf(LongArray.class);
            MemorySegment seg = ArraySegments.of(sut);
            assertThat(seg.getAtIndex(PTypeIO.LE_LONG, 0)).isEqualTo(100L);
            assertThat(seg.getAtIndex(PTypeIO.LE_LONG, 1)).isEqualTo(999L);
            assertThat(seg.getAtIndex(PTypeIO.LE_LONG, 2)).isEqualTo(300L);
        }

        @Test
        void decode_missingMetadata_throws() {
            // Given — node with no metadata
            ArrayNode innerNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0}, null);
            ArrayNode patchedNode = ArrayNode.of(EncodingId.VORTEX_PATCHED, null,
                    new ArrayNode[]{innerNode, innerNode, innerNode, innerNode}, new int[]{}, null);
            MemorySegment seg = i32Segment(1, 2, 3);
            DecodeContext ctx = new DecodeContext(patchedNode, new DType.Primitive(PType.I32, false), 3,
                    new MemorySegment[]{seg}, EncodingRegistry.empty(), Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> new PatchedEncoding().decode(ctx))
                    .hasMessageContaining("missing metadata");
        }
    }
}
