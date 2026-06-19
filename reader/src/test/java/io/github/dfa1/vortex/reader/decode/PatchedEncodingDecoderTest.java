package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.reader.ReadRegistry;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.PatchedMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchedEncodingDecoderTest {

    private static final PatchedEncodingDecoder SUT = new PatchedEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(SUT, new PrimitiveEncodingDecoder());

    private static ByteBuffer patchedMeta(int nPatches, int nLanes, int offset) {
        return ByteBuffer.wrap(new PatchedMetadata(nPatches, nLanes, offset).encode());
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

    private static Array decode(int n, int[] innerI32, int[] laneOffsets, short[] patchIndices, int[] patchValues) {
        return decode(new DType.Primitive(PType.I32, false), n,
                i32Segment(innerI32), u32Segment(laneOffsets),
                u16Segment(patchIndices), i32Segment(patchValues),
                laneOffsets.length - 1);
    }

    private static Array decode(DType dtype, int n,
            MemorySegment inner, MemorySegment laneOffsets,
            MemorySegment patchIndices, MemorySegment patchValues,
            int nLanes) {
        int nPatches = (int) (patchIndices.byteSize() / 2);
        ByteBuffer meta = patchedMeta(nPatches, nLanes, 0);

        MemorySegment[] segments = {inner, laneOffsets, patchIndices, patchValues};

        ArrayNode innerNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode laneNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode idxNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{2});
        ArrayNode valNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{3});

        ArrayNode patchedNode = ArrayNode.of(EncodingId.VORTEX_PATCHED, meta,
                new ArrayNode[]{innerNode, laneNode, idxNode, valNode}, new int[]{});

        DecodeContext ctx = new DecodeContext(patchedNode, dtype, n, segments, REGISTRY, Arena.ofAuto());
        return SUT.decode(ctx);
    }

    @Test
    void decode_noPatches_returnsInnerUnchanged() {
        // Given
        int n = 4;
        int[] inner = {10, 20, 30, 40};

        // When
        Array result = decode(n, inner, new int[]{0, 0}, new short[]{}, new int[]{});

        // Then
        assertThat(result).isInstanceOf(IntArray.class);
        MemorySegment seg = ArraySegments.of(result);
        for (int i = 0; i < n; i++) {
            assertThat(seg.getAtIndex(PTypeIO.LE_INT, i)).as("index %d", i).isEqualTo(inner[i]);
        }
    }

    @Test
    void decode_singlePatch_overwrites() {
        // Given / When
        Array result = decode(4, new int[]{10, 20, 30, 40}, new int[]{0, 1}, new short[]{2}, new int[]{99});

        // Then
        MemorySegment seg = ArraySegments.of(result);
        assertThat(seg.getAtIndex(PTypeIO.LE_INT, 0)).isEqualTo(10);
        assertThat(seg.getAtIndex(PTypeIO.LE_INT, 1)).isEqualTo(20);
        assertThat(seg.getAtIndex(PTypeIO.LE_INT, 2)).isEqualTo(99);
        assertThat(seg.getAtIndex(PTypeIO.LE_INT, 3)).isEqualTo(40);
    }

    @Test
    void decode_multiplePatches_allApplied() {
        // Given / When
        Array result = decode(4, new int[]{0, 0, 0, 0}, new int[]{0, 2}, new short[]{0, 3}, new int[]{1, 7});

        // Then
        MemorySegment seg = ArraySegments.of(result);
        assertThat(seg.getAtIndex(PTypeIO.LE_INT, 0)).isEqualTo(1);
        assertThat(seg.getAtIndex(PTypeIO.LE_INT, 1)).isEqualTo(0);
        assertThat(seg.getAtIndex(PTypeIO.LE_INT, 2)).isEqualTo(0);
        assertThat(seg.getAtIndex(PTypeIO.LE_INT, 3)).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 1020, 1023, 1024, 1025, 2048})
    void decode_variousLengths_noPatches(int n) {
        // Given
        int[] inner = new int[n];

        // When
        Array result = decode(n, inner, new int[]{0, 0}, new short[]{}, new int[]{});

        // Then
        MemorySegment seg = ArraySegments.of(result);
        for (int i = 0; i < n; i++) {
            assertThat(seg.getAtIndex(PTypeIO.LE_INT, i)).as("index %d", i).isZero();
        }
    }

    @Test
    void decode_i64_singlePatch() {
        // Given
        DType dtype = new DType.Primitive(PType.I64, false);

        // When
        Array result = decode(dtype, 3, i64Segment(100L, 200L, 300L), u32Segment(0, 1),
                u16Segment((short) 1), i64Segment(999L), 1);

        // Then
        assertThat(result).isInstanceOf(LongArray.class);
        MemorySegment seg = ArraySegments.of(result);
        assertThat(seg.getAtIndex(PTypeIO.LE_LONG, 0)).isEqualTo(100L);
        assertThat(seg.getAtIndex(PTypeIO.LE_LONG, 1)).isEqualTo(999L);
        assertThat(seg.getAtIndex(PTypeIO.LE_LONG, 2)).isEqualTo(300L);
    }

    @Test
    void decode_missingMetadata_throws() {
        // Given
        ArrayNode innerNode = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode patchedNode = ArrayNode.of(EncodingId.VORTEX_PATCHED, null,
                new ArrayNode[]{innerNode, innerNode, innerNode, innerNode}, new int[]{});
        MemorySegment seg = i32Segment(1, 2, 3);
        DecodeContext ctx = new DecodeContext(patchedNode, new DType.Primitive(PType.I32, false), 3,
                new MemorySegment[]{seg}, ReadRegistry.empty(), Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx)).hasMessageContaining("missing metadata");
    }
}
