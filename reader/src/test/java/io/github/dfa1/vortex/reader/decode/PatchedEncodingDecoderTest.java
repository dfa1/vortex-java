package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoPatchedMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchedEncodingDecoderTest {

    private static final PatchedEncodingDecoder SUT = new PatchedEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(SUT, new PrimitiveEncodingDecoder());

    private static MemorySegment patchedMeta(int nPatches, int nLanes, int offset) {
        return MemorySegment.ofArray(new ProtoPatchedMetadata(nPatches, nLanes, offset).encode());
    }





    private static Array decode(int n, int[] innerI32, int[] laneOffsets, short[] patchIndices, int[] patchValues) {
        return decode(DType.I32, n,
                TestSegments.leInts(innerI32), TestSegments.leInts(laneOffsets),
                TestSegments.leShorts(patchIndices), TestSegments.leInts(patchValues),
                laneOffsets.length - 1);
    }

    private static Array decode(DType dtype, int n,
            MemorySegment inner, MemorySegment laneOffsets,
            MemorySegment patchIndices, MemorySegment patchValues,
            int nLanes) {
        int nPatches = (int) (patchIndices.byteSize() / 2);
        MemorySegment meta = patchedMeta(nPatches, nLanes, 0);

        MemorySegment[] segments = {inner, laneOffsets, patchIndices, patchValues};

        ArrayNode innerNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode laneNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode idxNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{2});
        ArrayNode valNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{3});

        ArrayNode patchedNode = new ArrayNode(EncodingId.VORTEX_PATCHED, meta,
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
        IntArray ints = (IntArray) result;
        for (int i = 0; i < n; i++) {
            assertThat(ints.getInt(i)).as("index %d", i).isEqualTo(inner[i]);
        }
    }

    /// With nothing to patch, the output is byte-for-byte the inner child, so the decoder must
    /// alias it rather than allocate `n * elemBytes` and copy into a duplicate (#337). Proven by
    /// mutating the source after decode: a copy would not see the change.
    @Test
    void decode_noPatches_aliasesTheInnerChildInsteadOfCopying() {
        // Given — a mutable inner buffer, so a later write reveals whether it was copied
        byte[] backing = new byte[4 * Integer.BYTES];
        MemorySegment inner = MemorySegment.ofArray(backing);
        for (int i = 0; i < 4; i++) {
            inner.setAtIndex(VortexFormat.LE_INT, i, (i + 1) * 10);
        }

        // When
        Array result = decodeNoPatches(inner, 4);
        inner.setAtIndex(VortexFormat.LE_INT, 2, 999);

        // Then
        assertThat(((IntArray) result).getInt(2)).isEqualTo(999);
    }

    /// An inner child longer than the row count is sliced to exactly `n` elements, so the
    /// `Materialized*` accessors keep their `length == elementCount` fast path instead of
    /// falling into the broadcast-modulo branch.
    @Test
    void decode_noPatches_innerLongerThanRowCount_isSlicedToRowCount() {
        // Given — 6 elements on the wire, 4 rows declared
        MemorySegment inner = TestSegments.leInts(10, 20, 30, 40, 50, 60);

        // When
        Array result = decodeNoPatches(inner, 4);

        // Then
        assertThat(result.length()).isEqualTo(4L);
        assertThat(result.segmentIfPresent()).hasValueSatisfying(
                seg -> assertThat(seg.byteSize()).isEqualTo(4L * Integer.BYTES));
        assertThat(((IntArray) result).getInt(3)).isEqualTo(40);
    }

    /// The alias is only safe when the child covers every row. A `ConstantEncoding` child holds
    /// one element for any row count, so that case must still fan out through the copy.
    @Test
    void decode_noPatches_undersizedInner_stillBroadcasts() {
        // Given — a single inner element for 4 rows
        MemorySegment inner = TestSegments.leInts(7);

        // When
        Array result = decodeNoPatches(inner, 4);

        // Then
        IntArray ints = (IntArray) result;
        for (int i = 0; i < 4; i++) {
            assertThat(ints.getInt(i)).as("index %d", i).isEqualTo(7);
        }
    }

    private static Array decodeNoPatches(MemorySegment inner, int n) {
        return decode(DType.I32, n, inner, TestSegments.leInts(0, 0),
                TestSegments.leShorts(), TestSegments.leInts(), 1);
    }

    @Test
    void decode_singlePatch_overwrites() {
        // Given / When
        Array result = decode(4, new int[]{10, 20, 30, 40}, new int[]{0, 1}, new short[]{2}, new int[]{99});

        // Then
        IntArray ints = (IntArray) result;
        assertThat(ints.getInt(0)).isEqualTo(10);
        assertThat(ints.getInt(1)).isEqualTo(20);
        assertThat(ints.getInt(2)).isEqualTo(99);
        assertThat(ints.getInt(3)).isEqualTo(40);
    }

    @Test
    void decode_multiplePatches_allApplied() {
        // Given / When
        Array result = decode(4, new int[]{0, 0, 0, 0}, new int[]{0, 2}, new short[]{0, 3}, new int[]{1, 7});

        // Then
        IntArray ints = (IntArray) result;
        assertThat(ints.getInt(0)).isEqualTo(1);
        assertThat(ints.getInt(1)).isZero();
        assertThat(ints.getInt(2)).isZero();
        assertThat(ints.getInt(3)).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 1020, 1023, 1024, 1025, 2048})
    void decode_variousLengths_noPatches(int n) {
        // Given
        int[] inner = new int[n];

        // When
        Array result = decode(n, inner, new int[]{0, 0}, new short[]{}, new int[]{});

        // Then
        IntArray ints = (IntArray) result;
        for (int i = 0; i < n; i++) {
            assertThat(ints.getInt(i)).as("index %d", i).isZero();
        }
    }

    @Test
    void decode_i64_singlePatch() {
        // Given
        DType dtype = DType.I64;

        // When
        Array result = decode(dtype, 3, TestSegments.leLongs(100L, 200L, 300L), TestSegments.leInts(0, 1),
                TestSegments.leShorts((short) 1), TestSegments.leLongs(999L), 1);

        // Then
        assertThat(result).isInstanceOf(LongArray.class);
        LongArray longs = (LongArray) result;
        assertThat(longs.getLong(0)).isEqualTo(100L);
        assertThat(longs.getLong(1)).isEqualTo(999L);
        assertThat(longs.getLong(2)).isEqualTo(300L);
    }

    @Test
    void decode_missingMetadata_throws() {
        // Given
        ArrayNode innerNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode patchedNode = new ArrayNode(EncodingId.VORTEX_PATCHED, null,
                new ArrayNode[]{innerNode, innerNode, innerNode, innerNode}, new int[]{});
        MemorySegment seg = TestSegments.leInts(1, 2, 3);
        DecodeContext ctx = new DecodeContext(patchedNode, DType.I32, 3,
                new MemorySegment[]{seg}, ReadRegistry.empty(), Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx)).hasMessageContaining("missing metadata");
    }
}
