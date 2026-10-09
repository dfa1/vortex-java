package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.UnionArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnionEncodingDecoderTest {

    private static final UnionEncodingDecoder SUT = new UnionEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

    // Non-consecutive type ids, as Rust allows: a decoder indexing variants by type id would
    // read variant 5 and 7 out of bounds.
    private static final DType.Union UNION = new DType.Union(
            List.of(ColumnName.of("a"), ColumnName.of("b")), List.of(DType.I64, DType.I64), List.of(5, 7), false);

    @Test
    void encodingId_isVortexUnion() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_UNION);
    }

    @Test
    void decode_selectsEachRowsVariantByTypeId() {
        // Given — rows pick b, a, b; each variant child is as long as the union (sparse union)
        MemorySegment[] segs = {
                MemorySegment.ofArray(new byte[]{7, 5, 7}),
                TestSegments.leLongs(10, 11, 12),
                TestSegments.leLongs(20, 21, 22)};

        // When
        UnionArray result = (UnionArray) decode(UNION, 3, segs, primitiveNode(0), primitiveNode(1), primitiveNode(2));

        // Then
        long[] values = new long[3];
        for (int row = 0; row < 3; row++) {
            values[row] = ((LongArray) result.variant(result.variantIndex(row))).getLong(row);
        }
        assertThat(values).containsExactly(20, 11, 22);
        assertThat(result.isValid(0)).isTrue();
    }

    @Test
    void decode_nullableUnion_nullTagIsNullRow() {
        // Given — a nullable union: its type ids carry the validity (row 1 null)
        DType.Union nullable = (DType.Union) UNION.asNullable();
        MemorySegment[] segs = {
                MemorySegment.ofArray(new byte[]{5, 0, 7}),
                MemorySegment.ofArray(new byte[]{0b101}),
                TestSegments.leLongs(10, 11, 12),
                TestSegments.leLongs(20, 21, 22)};
        ArrayNode validity = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{1});
        ArrayNode typeIds = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[]{validity}, new int[]{0});

        // When
        UnionArray result = (UnionArray) decode(nullable, 3, segs, typeIds, primitiveNode(2), primitiveNode(3));

        // Then
        assertThat(List.of(result.isValid(0), result.isValid(1), result.isValid(2))).containsExactly(true, false, true);
        assertThat(result.typeId(2)).isEqualTo(7);
    }

    @Test
    void limited_keepsTypeIdsAndVariantsAligned() {
        // Given
        MemorySegment[] segs = {
                MemorySegment.ofArray(new byte[]{7, 5, 7}),
                TestSegments.leLongs(10, 11, 12),
                TestSegments.leLongs(20, 21, 22)};
        UnionArray union = (UnionArray) decode(UNION, 3, segs, primitiveNode(0), primitiveNode(1), primitiveNode(2));

        // When
        UnionArray result = (UnionArray) Array.limited(union, 2);

        // Then
        assertThat(result.length()).isEqualTo(2);
        assertThat(result.typeIds().length()).isEqualTo(2);
        assertThat(((LongArray) result.variant(result.variantIndex(1))).getLong(1)).isEqualTo(11);
    }

    @Nested
    class AdversarialInput {

        @Test
        void unknownTypeId_throwsOnAccess() {
            // Given — tag 6 names no variant; Rust does not validate tags at construction either
            MemorySegment[] segs = {
                    MemorySegment.ofArray(new byte[]{6}),
                    TestSegments.leLongs(10),
                    TestSegments.leLongs(20)};
            UnionArray union = (UnionArray) decode(UNION, 1, segs, primitiveNode(0), primitiveNode(1), primitiveNode(2));

            // When / Then
            assertThatThrownBy(() -> union.variantIndex(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unknown type id 6");
        }

        @Test
        void missingVariantChild_throws() {
            // Given — two variants, but only type_ids and one variant child
            MemorySegment[] segs = {MemorySegment.ofArray(new byte[]{5}), TestSegments.leLongs(10)};
            ArrayNode typeIds = primitiveNode(0);
            ArrayNode variant = primitiveNode(1);

            // When / Then
            assertThatThrownBy(() -> decode(UNION, 1, segs, typeIds, variant))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("expected 3 children");
        }

        @Test
        void buffers_throw() {
            // Given — Rust's union carries no buffers of its own
            MemorySegment[] segs = {
                    MemorySegment.ofArray(new byte[]{5}), TestSegments.leLongs(10), TestSegments.leLongs(20)};
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_UNION, null,
                    new ArrayNode[]{primitiveNode(0), primitiveNode(1), primitiveNode(2)}, new int[]{0});
            DecodeContext ctx = new DecodeContext(node, UNION, 1, segs, REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("expects no buffers");
        }

        @Test
        void metadata_throws() {
            // Given — nor metadata
            MemorySegment[] segs = {
                    MemorySegment.ofArray(new byte[]{5}), TestSegments.leLongs(10), TestSegments.leLongs(20)};
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_UNION, MemorySegment.ofArray(new byte[]{1}),
                    new ArrayNode[]{primitiveNode(0), primitiveNode(1), primitiveNode(2)}, new int[0]);
            DecodeContext ctx = new DecodeContext(node, UNION, 1, segs, REGISTRY, Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("expects no metadata");
        }

        @Test
        void nonUnionDtype_throws() {
            // Given
            MemorySegment[] segs = {MemorySegment.ofArray(new byte[]{5})};
            ArrayNode typeIds = primitiveNode(0);

            // When / Then
            assertThatThrownBy(() -> decode(DType.I64, 1, segs, typeIds))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("expected union dtype");
        }
    }

    private static Array decode(DType dtype, long rowCount, MemorySegment[] segs, ArrayNode... children) {
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_UNION, null, children, new int[0]);
        return SUT.decode(new DecodeContext(node, dtype, rowCount, segs, REGISTRY, Arena.ofAuto()));
    }

    private static ArrayNode primitiveNode(int bufferIndex) {
        return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{bufferIndex});
    }
}
