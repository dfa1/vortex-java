package io.github.dfa1.vortex.core.fbs;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/// Each rule of [FbsVerifier], driven through the generated `getRootAsX` entry points: a valid buffer
/// from [FbsBuilder] with one field corrupted in place, so every case fails on exactly one rule.
class FbsVerifierTest {

    @Test
    void builderOutput_passes() {
        // Given — a layout using every field kind it has: scalars, a byte vector, a u32 vector and a
        // vector of child tables
        MemorySegment seg = layout(new long[]{1, 2, 3}, 2);

        // When
        FbsLayout result = FbsLayout.getRootAsFbsLayout(seg);

        // Then
        assertThat(result.childrenLength()).isEqualTo(2);
        assertThat(result.segments(2)).isEqualTo(3);
    }

    @Nested
    class Tables {

        @Test
        void bufferTooShortForRootOffset_rejected() {
            // Given
            MemorySegment seg = MemorySegment.ofArray(new byte[3]);

            // When / Then
            assertMalformed(seg, "offset");
        }

        @Test
        void rootOffsetPastBuffer_rejected() {
            // Given
            MemorySegment seg = layout(new long[]{1}, 0);
            seg.set(LE_INT, 0, (int) seg.byteSize());

            // When / Then
            assertMalformed(seg, "offset target");
        }

        @Test
        void vtableOutsideBuffer_rejected() {
            // Given — the root's soffset points a vtable far before the buffer start
            MemorySegment seg = layout(new long[]{1}, 0);
            seg.set(LE_INT, rootPosition(seg), 1_000_000);

            // When / Then
            assertMalformed(seg, "vtable");
        }

        @Test
        void vtableSmallerThanItsHeader_rejected() {
            // Given — a vtable claiming 2 bytes, too short for its own size + object-size header
            MemorySegment seg = layout(new long[]{1}, 0);
            seg.set(LE_SHORT, vtable(seg), (short) 2);

            // When / Then
            assertMalformed(seg, "vtable size 2");
        }

        @Test
        void objectPastBuffer_rejected() {
            // Given — the vtable's object size runs the table past the buffer end
            MemorySegment seg = layout(new long[]{1}, 0);
            seg.set(LE_SHORT, vtable(seg) + 2, (short) 0xFFFF);

            // When / Then
            assertMalformed(seg, "table object");
        }

        @Test
        void fieldPastBuffer_rejected() {
            // Given — the row_count slot (vtable offset 6) points 8 bytes from the buffer end
            // minus 4, so the u64 field straddles it
            MemorySegment seg = layout(new long[]{1}, 0);
            long table = rootPosition(seg);
            seg.set(LE_SHORT, vtable(seg) + 6, (short) (seg.byteSize() - 4 - table));
            seg.set(LE_SHORT, vtable(seg) + 2, (short) (seg.byteSize() - table));

            // When / Then
            assertMalformed(seg, "field 'row_count'");
        }
    }

    @Nested
    class VectorsAndStrings {

        @Test
        void vectorLengthPastBuffer_rejected() {
            // Given — the segments vector's length prefix claims 100 000 u32s
            MemorySegment seg = layout(new long[]{1, 2}, 0);
            seg.set(LE_INT, vectorStart(seg, FbsLayout.getRootAsFbsLayout(seg), 12), 100_000);

            // When / Then
            assertMalformed(seg, "vector of 100000 elements");
        }

        @Test
        void stringWithoutNulTerminator_rejected() {
            // Given — an ArraySpec id whose terminating NUL is overwritten
            FbsBuilder b = new FbsBuilder(64);
            b.finish(FbsArraySpec.createFbsArraySpec(b, b.createString("vortex.primitive")));
            MemorySegment seg = MemorySegment.ofArray(b.sizedByteArray());
            long string = vectorStart(seg, FbsArraySpec.getRootAsFbsArraySpec(seg), 4);
            seg.set(ValueLayout.JAVA_BYTE, string + 4 + "vortex.primitive".length(), (byte) 'x');

            // When / Then
            assertThatThrownBy(() -> FbsArraySpec.getRootAsFbsArraySpec(seg))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("not NUL-terminated");
        }

        @Test
        void requiredFieldMissing_rejected() {
            // Given — an ArraySpec without its `id: string (required)`
            FbsBuilder b = new FbsBuilder(64);
            b.finish(FbsArraySpec.createFbsArraySpec(b, 0));
            MemorySegment seg = MemorySegment.ofArray(b.sizedByteArray());

            // When / Then
            assertThatThrownBy(() -> FbsArraySpec.getRootAsFbsArraySpec(seg))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("required field 'id' missing");
        }
    }

    @Nested
    class Unions {

        @Test
        void selectedMemberIsVerified() {
            // Given — a Struct_ dtype whose names vector claims far more strings than it holds: only
            // the union member's own verify can see it
            FbsBuilder b = new FbsBuilder(128);
            int names = FbsStruct.createNamesVector(b, new int[]{b.createString("a")});
            int dtypes = FbsStruct.createDtypesVector(b, new int[0]);
            int struct = FbsStruct.createFbsStruct(b, names, dtypes, false);
            b.finish(FbsDType.createFbsDType(b, FbsType.FbsStruct, struct));
            MemorySegment seg = MemorySegment.ofArray(b.sizedByteArray());
            FbsStruct member = FbsDType.getRootAsFbsDType(seg).type(new FbsStruct());
            seg.set(LE_INT, vectorStart(seg, member, 4), 100_000);

            // When / Then
            assertThatThrownBy(() -> FbsDType.getRootAsFbsDType(seg))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("vector of 100000 elements");
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "type {0}, value present {1}")
        @org.junit.jupiter.params.provider.CsvSource({"7, false", "0, true"})
        void typeAndValueDisagree_rejected(byte type, boolean withValue) {
            // Given — a member type with no value, or a value with no member type: Rust's
            // visit_union rejects both (InconsistentUnion); the accessors would return a null member
            FbsBuilder b = new FbsBuilder(64);
            int value = withValue ? FbsNull.createFbsNull(b) : 0;
            b.finish(FbsDType.createFbsDType(b, type, value));
            MemorySegment seg = MemorySegment.ofArray(b.sizedByteArray());

            // When / Then
            assertThatThrownBy(() -> FbsDType.getRootAsFbsDType(seg))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("union 'type' type " + type + " inconsistent with its value");
        }

        @Test
        void unknownMemberIsAccepted() {
            // Given — a discriminator this schema does not know: like flatc's verifiers, accepted,
            // since no accessor reads its value (the reader rejects the dtype itself)
            FbsBuilder b = new FbsBuilder(64);
            int nul = FbsNull.createFbsNull(b);
            b.finish(FbsDType.createFbsDType(b, (byte) 99, nul));
            MemorySegment seg = MemorySegment.ofArray(b.sizedByteArray());

            // When / Then
            assertThatCode(() -> FbsDType.getRootAsFbsDType(seg)).doesNotThrowAnyException();
        }
    }

    @Nested
    class Limits {

        @Test
        void nestingAtDepthLimit_passes() {
            // Given
            MemorySegment seg = nestedLayouts(FbsVerifier.MAX_DEPTH);

            // When / Then
            assertThatCode(() -> FbsLayout.getRootAsFbsLayout(seg)).doesNotThrowAnyException();
        }

        @Test
        void nestingPastDepthLimit_rejected() {
            // Given
            MemorySegment seg = nestedLayouts(FbsVerifier.MAX_DEPTH + 1);

            // When / Then
            assertMalformed(seg, "nest deeper than 64");
        }

        @Test
        void tableCountPastLimit_rejected() {
            // Given — one child table referenced MAX_TABLES times: a 4 MB buffer that a walk without
            // the table cap would visit a million times over (each visit counts, as in Rust)
            FbsBuilder b = new FbsBuilder(8 * 1024 * 1024);
            int leaf = FbsLayout.createFbsLayout(b, 0, 1L, 0, 0, 0);
            int[] children = new int[FbsVerifier.MAX_TABLES];
            Arrays.fill(children, leaf);
            int childrenVector = FbsLayout.createChildrenVector(b, children);
            b.finish(FbsLayout.createFbsLayout(b, 0, 1L, 0, childrenVector, 0));
            MemorySegment seg = b.dataSegment();

            // When / Then
            assertMalformed(seg, "more than 1000000 tables");
        }
    }

    private static void assertMalformed(MemorySegment seg, String reason) {
        assertThatThrownBy(() -> FbsLayout.getRootAsFbsLayout(seg))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("malformed flatbuffer: ")
                .hasMessageContaining(reason);
    }

    /// A layout with the given segment ids, a 3-byte metadata vector and `children` leaf children.
    private static MemorySegment layout(long[] segments, int children) {
        FbsBuilder b = new FbsBuilder(256);
        int[] leaves = new int[children];
        for (int i = 0; i < children; i++) {
            leaves[i] = FbsLayout.createFbsLayout(b, 0, 1L, 0, 0, 0);
        }
        int childrenVector = FbsLayout.createChildrenVector(b, leaves);
        int metadata = FbsLayout.createMetadataVector(b, new byte[]{1, 2, 3});
        int segmentsVector = FbsLayout.createSegmentsVector(b, segments);
        b.finish(FbsLayout.createFbsLayout(b, 0, 7L, metadata, childrenVector, segmentsVector));
        return MemorySegment.ofArray(b.sizedByteArray());
    }

    /// A chain of `tables` layouts, each the single child of the next.
    private static MemorySegment nestedLayouts(int tables) {
        FbsBuilder b = new FbsBuilder(tables * 32 + 64);
        int current = FbsLayout.createFbsLayout(b, 0, 1L, 0, 0, 0);
        for (int i = 1; i < tables; i++) {
            current = FbsLayout.createFbsLayout(b, 0, 1L, 0, FbsLayout.createChildrenVector(b, new int[]{current}), 0);
        }
        b.finish(current);
        return MemorySegment.ofArray(b.sizedByteArray());
    }

    private static long rootPosition(MemorySegment seg) {
        return seg.get(LE_INT, 0);
    }

    private static long vtable(MemorySegment seg) {
        long table = rootPosition(seg);
        return table - seg.get(LE_INT, table);
    }

    /// Position of the length prefix of the vector (or string) in `table`'s `slot`.
    private static long vectorStart(MemorySegment seg, FbsTable table, int slot) {
        long at = table.pos + table.fieldOffset(slot);
        return at + seg.get(LE_INT, at);
    }
}
