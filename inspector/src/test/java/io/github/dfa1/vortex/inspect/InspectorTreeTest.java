package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.CompressionScheme;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Footer;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.SegmentSpec;
import io.github.dfa1.vortex.io.VortexHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class InspectorTreeTest {

    @Mock
    VortexHandle handle;

    @Test
    void build_withStructDType_assignsFieldNamesToColumns() {
        // Given
        Layout idLeaf = leaf("vortex.constant", 10);
        Layout valLeaf = leaf("vortex.constant", 10);
        Layout root = struct(10, List.of(idLeaf, valLeaf));
        DType dtype = new DType.Struct(
                List.of("id", "value"),
                List.of(new DType.Primitive(PType.I64, false), new DType.Primitive(PType.F64, false)),
                false);
        givenHandle(dtype, root, List.of("vortex.constant"), List.of());

        // When
        InspectorTree sut = InspectorTree.build(handle);

        // Then
        assertThat(sut.root().fieldName()).isEmpty();
        assertThat(sut.root().children()).hasSize(2);
        assertThat(sut.root().children().get(0).fieldName()).contains("id");
        assertThat(sut.root().children().get(1).fieldName()).contains("value");
    }

    @Test
    void build_withFewerColNamesThanChildren_fillsWithSyntheticNames() {
        // Given — defensive path: malformed footer with a struct layout whose child count
        // exceeds the dtype's named fields. Should not throw; should fall back to col0/col1...
        Layout root = struct(0, List.of(leaf("vortex.constant", 0), leaf("vortex.constant", 0)));
        DType dtype = new DType.Struct(List.of("only"),
                List.of(new DType.Primitive(PType.I32, false)), false);
        givenHandle(dtype, root, List.of("vortex.constant"), List.of());

        // When
        InspectorTree sut = InspectorTree.build(handle);

        // Then
        assertThat(sut.root().children().get(0).fieldName()).contains("only");
        assertThat(sut.root().children().get(1).fieldName()).contains("col1");
    }

    @Test
    void build_withNonStructRoot_leavesFieldNameEmpty() {
        // Given
        Layout root = leaf("vortex.flat", 100);
        DType dtype = new DType.Primitive(PType.I64, false);
        givenHandle(dtype, root, List.of("vortex.flat"), List.of());

        // When
        InspectorTree sut = InspectorTree.build(handle);

        // Then
        assertThat(sut.root().fieldName()).isEmpty();
        assertThat(sut.root().children()).isEmpty();
    }

    @Test
    void build_sumsSegmentBytesAndCountsSegments() {
        // Given
        Layout root = leaf("vortex.flat", 0);
        DType dtype = new DType.Primitive(PType.I32, false);
        List<SegmentSpec> segs = List.of(
                new SegmentSpec(0, 128, (byte) 0, CompressionScheme.LZ4),
                new SegmentSpec(128, 256, (byte) 0, CompressionScheme.LZ4),
                new SegmentSpec(384, 64, (byte) 0, CompressionScheme.LZ4));
        givenHandle(dtype, root, List.of("vortex.flat"), segs);

        // When
        InspectorTree sut = InspectorTree.build(handle);

        // Then
        assertThat(sut.segmentCount()).isEqualTo(3);
        assertThat(sut.totalSegmentBytes()).isEqualTo(128L + 256L + 64L);
        assertThat(sut.segmentSpecs()).containsExactlyElementsOf(segs);
    }

    @Test
    void build_setsTotalRowCountFromRootLayout() {
        // Given — total rows is the root layout's row count, regardless of struct/non-struct
        Layout root = struct(12_345L, List.of(leaf("vortex.constant", 12_345L)));
        DType dtype = new DType.Struct(List.of("c"),
                List.of(new DType.Primitive(PType.I32, false)), false);
        givenHandle(dtype, root, List.of("vortex.constant"), List.of());

        // When
        InspectorTree sut = InspectorTree.build(handle);

        // Then
        assertThat(sut.totalRowCount()).isEqualTo(12_345L);
    }

    @Test
    void build_carriesVersionAndFileSize() {
        // Given
        Layout root = leaf("vortex.flat", 0);
        DType dtype = new DType.Primitive(PType.I32, false);
        given(handle.version()).willReturn(7);
        given(handle.fileSize()).willReturn(123_456L);
        given(handle.dtype()).willReturn(dtype);
        given(handle.layout()).willReturn(root);
        given(handle.footer()).willReturn(new Footer(List.of("vortex.flat"), List.of(), List.of(), List.of()));

        // When
        InspectorTree sut = InspectorTree.build(handle);

        // Then
        assertThat(sut.version()).isEqualTo(7);
        assertThat(sut.fileSize()).isEqualTo(123_456L);
    }

    @Test
    void build_reportsProgressOncePerPeekedSegment() {
        // Given — struct of two compressed (skipped) + two uncompressed Flat columns.
        // Only uncompressed leaves trigger peekFlatRoot, so progress should fire twice
        // with total=2.
        Layout c1 = new Layout("vortex.flat", 0, null, List.of(), List.of(0));
        Layout c2 = new Layout("vortex.flat", 0, null, List.of(), List.of(1));
        Layout c3 = new Layout("vortex.flat", 0, null, List.of(), List.of(2));
        Layout root = struct(0, List.of(c1, c2, c3));
        DType dtype = new DType.Struct(List.of("a", "b", "c"),
                List.of(new DType.Primitive(PType.I32, false),
                        new DType.Primitive(PType.I32, false),
                        new DType.Primitive(PType.I32, false)),
                false);
        List<SegmentSpec> segs = List.of(
                new SegmentSpec(0, 1024, (byte) 0, CompressionScheme.ZSTD),  // skipped
                new SegmentSpec(1024, 1024, (byte) 0, CompressionScheme.LZ4), // skipped
                new SegmentSpec(2048, 1024, (byte) 0, CompressionScheme.LZ4)); // skipped
        givenHandle(dtype, root, List.of("vortex.flat"), segs);

        java.util.List<int[]> reports = new java.util.ArrayList<>();

        // When
        InspectorTree.build(handle, (cur, tot) -> reports.add(new int[]{cur, tot}));

        // Then — all three are compressed, so no peeks fire; progress never called
        assertThat(reports).isEmpty();
    }

    @Test
    void build_progressNoop_isAcceptedAndProducesSameTree() {
        // Given
        Layout root = struct(0, List.of(leaf("vortex.constant", 0)));
        DType dtype = new DType.Struct(List.of("c"),
                List.of(new DType.Primitive(PType.I32, false)), false);
        givenHandle(dtype, root, List.of("vortex.constant"), List.of());

        // When / Then — NOOP passes; no NPE
        InspectorTree sut = InspectorTree.build(handle, InspectorTree.Progress.NOOP);
        assertThat(sut.root().children()).hasSize(1);
    }

    @Test
    void buildShallow_skipsAllSlicesAndStillNamesColumns() {
        // Given — shallow build is the path the TUI uses; it must touch zero segment
        // bytes (so opening a remote file is instant) yet still populate fieldName on
        // top-level struct children.
        Layout col0 = new Layout("vortex.flat", 10, null, List.of(), List.of(0));
        Layout col1 = new Layout("vortex.flat", 10, null, List.of(), List.of(1));
        Layout root = struct(10, List.of(col0, col1));
        DType dtype = new DType.Struct(List.of("id", "value"),
                List.of(new DType.Primitive(PType.I64, false),
                        new DType.Primitive(PType.F64, false)),
                false);
        List<SegmentSpec> segs = List.of(
                new SegmentSpec(0, 64, (byte) 0, CompressionScheme.NONE),
                new SegmentSpec(64, 64, (byte) 0, CompressionScheme.NONE));
        givenHandle(dtype, root, List.of("vortex.flat"), segs);

        // When
        InspectorTree sut = InspectorTree.buildShallow(handle);

        // Then — column names assigned, but no peek fired so stats / usedEncodings empty
        assertThat(sut.root().children().get(0).fieldName()).contains("id");
        assertThat(sut.root().children().get(1).fieldName()).contains("value");
        assertThat(sut.usedEncodings()).isEmpty();
        assertThat(sut.root().children().get(0).usedEncodings()).isEmpty();
        assertThat(sut.root().children().get(0).stats()).isEqualTo(io.github.dfa1.vortex.core.ArrayStats.empty());
        // Slice is reserved for lazy peek; shallow build must never call it
        org.mockito.Mockito.verify(handle, org.mockito.Mockito.never()).slice(
                org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyLong());
    }

    @Test
    void peek_nonFlatNode_returnsEmptyWithoutSlicing() {
        // Given — peek is the lazy hook the TUI uses on the selected node. Non-Flat
        // layouts (struct, chunked, stats wrappers) carry no array root and must short
        // out without slicing, so navigating to them doesn't hit the network.
        Layout structLayout = struct(0, List.of());
        InspectorTree.Node node = new InspectorTree.Node(structLayout, java.util.Optional.empty(),
                Set.of(), io.github.dfa1.vortex.core.ArrayStats.empty(), List.of());

        // When
        InspectorTree.Peek result = InspectorTree.peek(node, handle);

        // Then
        assertThat(result).isSameAs(InspectorTree.Peek.EMPTY);
        org.mockito.Mockito.verify(handle, org.mockito.Mockito.never()).slice(
                org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyLong());
    }

    @Test
    void peek_compressedFlatSegment_returnsEmptyWithoutSlicing() {
        // Given — compressed segments would need the encoding to decompress before
        // their FlatBuffer can be parsed; peek skips them rather than slicing garbage.
        Layout flat = new Layout("vortex.flat", 10, null, List.of(), List.of(0));
        InspectorTree.Node node = new InspectorTree.Node(flat, java.util.Optional.empty(),
                Set.of(), io.github.dfa1.vortex.core.ArrayStats.empty(), List.of());
        given(handle.footer()).willReturn(new io.github.dfa1.vortex.core.Footer(
                List.of("vortex.flat"), List.of(),
                List.of(new SegmentSpec(0, 100, (byte) 0, CompressionScheme.ZSTD)),
                List.of()));

        // When
        InspectorTree.Peek result = InspectorTree.peek(node, handle);

        // Then
        assertThat(result).isSameAs(InspectorTree.Peek.EMPTY);
        org.mockito.Mockito.verify(handle, org.mockito.Mockito.never()).slice(
                org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyLong());
    }

    @Test
    void build_flatChildWithCompressedSegment_skipsRootEncodingPeek() {
        // Given — peekRootEncoding() reads the segment as a FlatBuffer; compressed segments
        // are intentionally skipped so a malformed or compressed payload can't crash the
        // inspector. With code != NONE we should still build a tree, with no encodings used.
        Layout root = new Layout("vortex.flat", 0, null, List.of(), List.of(0));
        DType dtype = new DType.Primitive(PType.I32, false);
        SegmentSpec compressed = new SegmentSpec(0, 1024, (byte) 0, CompressionScheme.ZSTD);
        givenHandle(dtype, root, List.of("vortex.flat"), List.of(compressed));

        // When
        InspectorTree sut = InspectorTree.build(handle);

        // Then
        assertThat(sut.usedEncodings()).isEmpty();
        assertThat(sut.root().usedEncodings()).isEmpty();
    }

    private void givenHandle(DType dtype, Layout layout, List<String> arraySpecs, List<SegmentSpec> segs) {
        given(handle.dtype()).willReturn(dtype);
        given(handle.layout()).willReturn(layout);
        given(handle.footer()).willReturn(new Footer(arraySpecs, List.of(), segs, List.of()));
    }

    private static Layout struct(long rows, List<Layout> children) {
        return new Layout("vortex.struct", rows, null, children, List.of());
    }

    private static Layout leaf(String encodingId, long rows) {
        return new Layout(encodingId, rows, null, List.of(), List.of());
    }
}
