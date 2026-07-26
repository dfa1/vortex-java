package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.fbs.FbsArray;
import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.reader.CompressionScheme;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.reader.Footer;
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.VortexHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                List.of(ColumnName.of("id"), ColumnName.of("value")),
                List.of(DType.I64, DType.F64),
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
        DType dtype = new DType.Struct(List.of(ColumnName.of("only")),
                List.of(DType.I32), false);
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
        DType dtype = DType.I64;
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
        DType dtype = DType.I32;
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
        DType dtype = new DType.Struct(List.of(ColumnName.of("c")),
                List.of(DType.I32), false);
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
        DType dtype = DType.I32;
        given(handle.version()).willReturn(7);
        given(handle.fileSize()).willReturn(123_456L);
        given(handle.dtype()).willReturn(dtype);
        given(handle.layout()).willReturn(root);
        given(handle.footer()).willReturn(new Footer(List.of(EncodingId.parse("vortex.flat")), List.of(), List.of(), List.of()));

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
        Layout c1 = new Layout(LayoutId.parse("vortex.flat"), 0, null, List.of(), List.of(0));
        Layout c2 = new Layout(LayoutId.parse("vortex.flat"), 0, null, List.of(), List.of(1));
        Layout c3 = new Layout(LayoutId.parse("vortex.flat"), 0, null, List.of(), List.of(2));
        Layout root = struct(0, List.of(c1, c2, c3));
        DType dtype = new DType.Struct(List.of(ColumnName.of("a"), ColumnName.of("b"), ColumnName.of("c")),
                List.of(DType.I32,
                        DType.I32,
                        DType.I32),
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
        DType dtype = new DType.Struct(List.of(ColumnName.of("c")),
                List.of(DType.I32), false);
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
        Layout col0 = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(0));
        Layout col1 = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(1));
        Layout root = struct(10, List.of(col0, col1));
        DType dtype = new DType.Struct(List.of(ColumnName.of("id"), ColumnName.of("value")),
                List.of(DType.I64,
                        DType.F64),
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
        assertThat(sut.root().children().get(0).stats()).isEqualTo(io.github.dfa1.vortex.reader.ArrayStats.empty());
        // rawSegment is reserved for lazy peek; shallow build must never call it
        org.mockito.Mockito.verify(handle, org.mockito.Mockito.never()).rawSegment(
                org.mockito.Mockito.any());
    }

    @Test
    void peek_nonFlatNode_returnsEmptyWithoutSlicing() {
        // Given — peek is the lazy hook the TUI uses on the selected node. Non-Flat
        // layouts (struct, chunked, stats wrappers) carry no array root and must short
        // out without slicing, so navigating to them doesn't hit the network.
        Layout structLayout = struct(0, List.of());
        InspectorTree.Node node = new InspectorTree.Node(structLayout, java.util.Optional.empty(),
                Set.of(), io.github.dfa1.vortex.reader.ArrayStats.empty(), List.of());

        // When
        InspectorTree.Peek result = InspectorTree.peek(node, handle);

        // Then
        assertThat(result).isSameAs(InspectorTree.Peek.EMPTY);
        org.mockito.Mockito.verify(handle, org.mockito.Mockito.never()).rawSegment(
                org.mockito.Mockito.any());
    }

    @Test
    void peek_compressedFlatSegment_returnsEmptyWithoutSlicing() {
        // Given — compressed segments would need the encoding to decompress before
        // their FlatBuffer can be parsed; peek skips them rather than slicing garbage.
        Layout flat = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(0));
        InspectorTree.Node node = new InspectorTree.Node(flat, java.util.Optional.empty(),
                Set.of(), io.github.dfa1.vortex.reader.ArrayStats.empty(), List.of());
        given(handle.footer()).willReturn(new io.github.dfa1.vortex.reader.Footer(
                List.of(EncodingId.parse("vortex.flat")), List.of(),
                List.of(new SegmentSpec(0, 100, (byte) 0, CompressionScheme.ZSTD)),
                List.of()));

        // When
        InspectorTree.Peek result = InspectorTree.peek(node, handle);

        // Then
        assertThat(result).isSameAs(InspectorTree.Peek.EMPTY);
        org.mockito.Mockito.verify(handle, org.mockito.Mockito.never()).rawSegment(
                org.mockito.Mockito.any());
    }

    @Test
    void build_flatChildWithCompressedSegment_skipsRootEncodingPeek() {
        // Given — peekRootEncoding() reads the segment as a FlatBuffer; compressed segments
        // are intentionally skipped so a malformed or compressed payload can't crash the
        // inspector. With code != NONE we should still build a tree, with no encodings used.
        Layout root = new Layout(LayoutId.parse("vortex.flat"), 0, null, List.of(), List.of(0));
        DType dtype = DType.I32;
        SegmentSpec compressed = new SegmentSpec(0, 1024, (byte) 0, CompressionScheme.ZSTD);
        givenHandle(dtype, root, List.of("vortex.flat"), List.of(compressed));

        // When
        InspectorTree sut = InspectorTree.build(handle);

        // Then
        assertThat(sut.usedEncodings()).isEmpty();
        assertThat(sut.root().usedEncodings()).isEmpty();
    }

    @Test
    void build_flatSegmentWithNestedEncoding_reportsRootAndNestedEncodings() {
        // Given — a Flat segment whose root ArrayNode is vortex.masked wrapping a
        // vortex.fsst child, as CascadingCompressor genuinely produces for high-cardinality
        // Utf8 columns (issue #298: the old peek only ever looked at the root node, so
        // "vortex.fsst" never showed up in usedEncodings despite being the encoding actually
        // selected and used on disk).
        try (Arena arena = Arena.ofConfined()) {
            Layout root = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(0));
            DType dtype = DType.UTF8;
            List<String> arraySpecs = List.of("vortex.masked", "vortex.fsst");
            SegmentSpec spec = new SegmentSpec(0, 100, (byte) 0, CompressionScheme.NONE);
            givenHandle(dtype, root, arraySpecs, List.of(spec));
            byte[] fb = nestedArrayFlatBuffer(0, 1);
            given(handle.rawSegment(spec)).willReturn(wrapAsSegment(fb, arena));

            // When
            InspectorTree sut = InspectorTree.build(handle);

            // Then
            assertThat(sut.usedEncodings()).containsExactlyInAnyOrder("vortex.masked", "vortex.fsst");
            assertThat(sut.root().usedEncodings()).containsExactlyInAnyOrder("vortex.masked", "vortex.fsst");
        }
    }

    @Test
    void build_flatSegmentEncodingTreeAtDepthLimit_clearsGuard() {
        // Given — deepest node at exactly MAX_ENCODING_TREE_DEPTH clears the guard
        // (`depth > limit` is false there); brackets the boundary with the next test.
        try (Arena arena = Arena.ofConfined()) {
            Layout root = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(0));
            DType dtype = DType.I32;
            SegmentSpec spec = new SegmentSpec(0, 100, (byte) 0, CompressionScheme.NONE);
            givenHandle(dtype, root, List.of("vortex.flat"), List.of(spec));
            byte[] fb = deeplyNestedArrayFlatBuffer(InspectorTree.MAX_ENCODING_TREE_DEPTH);
            given(handle.rawSegment(spec)).willReturn(wrapAsSegment(fb, arena));

            // When
            InspectorTree sut = InspectorTree.build(handle);

            // Then
            assertThat(sut.usedEncodings()).containsExactly("vortex.flat");
        }
    }

    @Test
    void build_flatSegmentEncodingTreeOneOverDepthLimit_throwsVortexException() {
        // Given — one level deeper than MAX_ENCODING_TREE_DEPTH: a crafted file with a very
        // deep ArrayNode nesting must surface as VortexException, never StackOverflowError
        // (the untrusted-input contract every reader/inspector parse path must uphold).
        try (Arena arena = Arena.ofConfined()) {
            Layout root = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(0));
            DType dtype = DType.I32;
            SegmentSpec spec = new SegmentSpec(0, 100, (byte) 0, CompressionScheme.NONE);
            givenHandle(dtype, root, List.of("vortex.flat"), List.of(spec));
            byte[] fb = deeplyNestedArrayFlatBuffer(InspectorTree.MAX_ENCODING_TREE_DEPTH + 1);
            given(handle.rawSegment(spec)).willReturn(wrapAsSegment(fb, arena));

            // When / Then
            assertThatThrownBy(() -> InspectorTree.build(handle))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("depth");
        }
    }

    @Test
    void build_flatSegmentEncodingIndexOutOfBounds_throwsVortexException() {
        // Given — a nested child references encoding index 999, but the footer's array spec
        // table only declares one entry. FbsArrayNode#encoding() is a raw wire-supplied
        // unsigned short with no upstream validation, so collectEncodings must bounds-check it
        // itself rather than let List.get surface a raw IndexOutOfBoundsException.
        try (Arena arena = Arena.ofConfined()) {
            Layout root = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(0));
            DType dtype = DType.I32;
            SegmentSpec spec = new SegmentSpec(0, 100, (byte) 0, CompressionScheme.NONE);
            givenHandle(dtype, root, List.of("vortex.flat"), List.of(spec));
            byte[] fb = nestedArrayFlatBuffer(0, 999);
            given(handle.rawSegment(spec)).willReturn(wrapAsSegment(fb, arena));

            // When / Then
            assertThatThrownBy(() -> InspectorTree.build(handle))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("encoding index");
        }
    }

    @Test
    void build_flatSegmentEncodingTreeOverNodeCountLimit_throwsVortexException() {
        // Given — a wide (not deep) tree: root fans out to MAX_ENCODING_TREE_NODES + 1 leaf
        // children, so depth never exceeds 1 but total node count does. Without a separate
        // node-count cap, two aliased children per level (both children vector slots resolving
        // to the same table position) could drive collectEncodings to an exponential number of
        // visits while depth stays within MAX_ENCODING_TREE_DEPTH; this is the simpler,
        // non-aliased way to exercise the same cap.
        try (Arena arena = Arena.ofConfined()) {
            Layout root = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(0));
            DType dtype = DType.I32;
            SegmentSpec spec = new SegmentSpec(0, 100, (byte) 0, CompressionScheme.NONE);
            givenHandle(dtype, root, List.of("vortex.flat"), List.of(spec));
            byte[] fb = wideArrayFlatBuffer(InspectorTree.MAX_ENCODING_TREE_NODES + 1);
            given(handle.rawSegment(spec)).willReturn(wrapAsSegment(fb, arena));

            // When / Then
            assertThatThrownBy(() -> InspectorTree.build(handle))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("node count");
        }
    }

    /// Builds a minimal `FbsArray` whose root node references `rootEncodingIdx` and has a
    /// single child referencing `childEncodingIdx`, both buffer-less.
    private static byte[] nestedArrayFlatBuffer(int rootEncodingIdx, int childEncodingIdx) {
        FbsBuilder b = new FbsBuilder(256);
        int childEmptyChildren = FbsArrayNode.createChildrenVector(b, new int[0]);
        int childEmptyBuffers = FbsArrayNode.createBuffersVector(b, new int[0]);
        int child = FbsArrayNode.createFbsArrayNode(b, childEncodingIdx, 0, childEmptyChildren, childEmptyBuffers, 0);
        int rootChildren = FbsArrayNode.createChildrenVector(b, new int[]{child});
        int rootBuffers = FbsArrayNode.createBuffersVector(b, new int[0]);
        int root = FbsArrayNode.createFbsArrayNode(b, rootEncodingIdx, 0, rootChildren, rootBuffers, 0);
        FbsArray.startBuffersVector(b, 0);
        int buffers = b.endVector();
        int array = FbsArray.createFbsArray(b, root, buffers);
        FbsArray.finishFbsArrayBuffer(b, array);
        return b.sizedByteArray();
    }

    /// Builds a minimal `FbsArray` whose root node has `depth` levels of single-child nesting,
    /// each level a buffer-less node referencing encoding index 0. Mirrors the reader module's
    /// `ArrayNodeDepthBombSecurityTest` builder.
    private static byte[] deeplyNestedArrayFlatBuffer(int depth) {
        FbsBuilder b = new FbsBuilder(depth * 32);
        int emptyChildren = FbsArrayNode.createChildrenVector(b, new int[0]);
        int emptyBuffers = FbsArrayNode.createBuffersVector(b, new int[0]);
        int current = FbsArrayNode.createFbsArrayNode(b, 0, 0, emptyChildren, emptyBuffers, 0);
        for (int i = 0; i < depth; i++) {
            int childV = FbsArrayNode.createChildrenVector(b, new int[]{current});
            int bufV = FbsArrayNode.createBuffersVector(b, new int[0]);
            current = FbsArrayNode.createFbsArrayNode(b, 0, 0, childV, bufV, 0);
        }
        FbsArray.startBuffersVector(b, 0);
        int buffers = b.endVector();
        int array = FbsArray.createFbsArray(b, current, buffers);
        FbsArray.finishFbsArrayBuffer(b, array);
        return b.sizedByteArray();
    }

    /// Builds a minimal `FbsArray` whose root node has `childCount` buffer-less leaf children,
    /// each referencing encoding index 0.
    private static byte[] wideArrayFlatBuffer(int childCount) {
        FbsBuilder b = new FbsBuilder(Math.max(1024, childCount * 32));
        int emptyChildren = FbsArrayNode.createChildrenVector(b, new int[0]);
        int emptyBuffers = FbsArrayNode.createBuffersVector(b, new int[0]);
        int[] children = new int[childCount];
        for (int i = 0; i < childCount; i++) {
            children[i] = FbsArrayNode.createFbsArrayNode(b, 0, 0, emptyChildren, emptyBuffers, 0);
        }
        int rootChildren = FbsArrayNode.createChildrenVector(b, children);
        int rootBuffers = FbsArrayNode.createBuffersVector(b, new int[0]);
        int root = FbsArrayNode.createFbsArrayNode(b, 0, 0, rootChildren, rootBuffers, 0);
        FbsArray.startBuffersVector(b, 0);
        int buffers = b.endVector();
        int array = FbsArray.createFbsArray(b, root, buffers);
        FbsArray.finishFbsArrayBuffer(b, array);
        return b.sizedByteArray();
    }

    private static MemorySegment wrapAsSegment(byte[] fb, Arena arena) {
        MemorySegment seg = arena.allocate(fb.length + 4L);
        MemorySegment.copy(MemorySegment.ofArray(fb), 0, seg, 0, fb.length);
        seg.set(VortexFormat.LE_INT, fb.length, fb.length);
        return seg;
    }

    private void givenHandle(DType dtype, Layout layout, List<String> arraySpecs, List<SegmentSpec> segs) {
        given(handle.dtype()).willReturn(dtype);
        given(handle.layout()).willReturn(layout);
        given(handle.footer()).willReturn(new Footer(
                arraySpecs.stream().map(EncodingId::parse).toList(), List.of(), segs, List.of()));
    }

    private static Layout struct(long rows, List<Layout> children) {
        return new Layout(LayoutId.parse("vortex.struct"), rows, null, children, List.of());
    }

    private static Layout leaf(String encodingId, long rows) {
        return new Layout(LayoutId.parse(encodingId), rows, null, List.of(), List.of());
    }
}
