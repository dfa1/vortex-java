package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.CompressionScheme;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.reader.SegmentSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VortexInspectorTest {

    @Test
    void render_struct_listsHeaderColumnsAndUsedEncodings() {
        // Given
        InspectorTree sut = struct2col(2, 4096L,
                List.of(
                        new SegmentSpec(0, 512, (byte) 0, CompressionScheme.NONE),
                        new SegmentSpec(512, 512, (byte) 0, CompressionScheme.LZ4)),
                Set.of("fastlanes.bitpacked", "vortex.constant"));

        // When
        String result = VortexInspector.render(sut);

        // Then
        assertThat(result)
                .contains("Vortex v2")
                .contains("4.0 KB")
                .contains("1000 rows")
                .contains("Schema:")
                .contains("id")
                .contains("value")
                .contains("Registered encodings: vortex.flat, fastlanes.bitpacked, vortex.constant")
                .contains("Used encodings: ")
                .contains("Segments: 2")
                .contains("[0] off=0  len=512 B  compression=NONE")
                .contains("[1] off=512  len=512 B  compression=LZ4")
                .contains("Layout:")
                .contains("struct (1000 rows)")
                .contains("[fastlanes.bitpacked]");
    }

    @Test
    void render_segmentTable_listsEverySegment() {
        // Given — verify table prints one line per segment with offset + size + compression
        List<SegmentSpec> specs = List.of(
                new SegmentSpec(0, 1024, (byte) 0, CompressionScheme.ZSTD),
                new SegmentSpec(1024, 2048, (byte) 0, CompressionScheme.NONE),
                new SegmentSpec(3072, 4096, (byte) 0, CompressionScheme.LZ4));
        InspectorTree sut = struct2col(1, 8192L, specs, Set.of());

        // When
        String result = VortexInspector.render(sut);

        // Then
        assertThat(result)
                .contains("[0] off=0  len=1.0 KB  compression=ZSTD")
                .contains("[1] off=1024  len=2.0 KB  compression=NONE")
                .contains("[2] off=3072  len=4.0 KB  compression=LZ4");
    }

    @Test
    void render_nonStruct_inlinesSingleColumnLayout() {
        // Given
        Layout leaf = new Layout(LayoutId.parse("vortex.flat"), 100, null, List.of(), List.of());
        InspectorTree.Node root = new InspectorTree.Node(leaf, Optional.empty(), Set.of(), ArrayStats.empty(), List.of());
        InspectorTree sut = new InspectorTree(
                1, 256L,
                DType.I32,
                List.of("vortex.flat"), Set.of(),
                List.of(new SegmentSpec(0, 256, (byte) 0, CompressionScheme.NONE)),
                100L, root);

        // When
        String result = VortexInspector.render(sut);

        // Then
        assertThat(result).contains("vortex.flat(100 rows)");
        assertThat(result).doesNotContain("struct (");
    }

    @Test
    void render_formatsBytesAcrossUnits() {
        // Given — bytes / KB / MB boundaries
        List<SegmentSpec> oneSeg = List.of(new SegmentSpec(0, 1, (byte) 0, CompressionScheme.NONE));
        InspectorTree small = struct2col(1, 512L, oneSeg, Set.of());
        InspectorTree medium = struct2col(1, 2048L, oneSeg, Set.of());
        InspectorTree large = struct2col(1, 5L * 1024 * 1024, oneSeg, Set.of());

        // When / Then
        assertThat(VortexInspector.render(small)).contains("512 B");
        assertThat(VortexInspector.render(medium)).contains("2.0 KB");
        assertThat(VortexInspector.render(large)).contains("5.0 MB");
    }

    @Test
    void render_chainsChildrenWithArrow() {
        // Given — nested zoned → chunked → flat chain
        Layout flat = new Layout(LayoutId.parse("vortex.flat"), 1000, null, List.of(), List.of());
        Layout chunked = new Layout(LayoutId.parse("vortex.chunked"), 1000, null, List.of(flat), List.of());
        Layout zoned = new Layout(LayoutId.parse("vortex.stats"), 1000, null, List.of(chunked), List.of());
        Layout structLayout = new Layout(LayoutId.parse("vortex.struct"), 1000, null, List.of(zoned), List.of());

        InspectorTree.Node flatN = new InspectorTree.Node(flat, Optional.empty(), Set.of(), ArrayStats.empty(), List.of());
        InspectorTree.Node chunkedN = new InspectorTree.Node(chunked, Optional.empty(), Set.of(), ArrayStats.empty(), List.of(flatN));
        InspectorTree.Node zonedN = new InspectorTree.Node(zoned, Optional.of("v"), Set.of(), ArrayStats.empty(), List.of(chunkedN));
        InspectorTree.Node rootN = new InspectorTree.Node(structLayout, Optional.empty(), Set.of(), ArrayStats.empty(), List.of(zonedN));

        InspectorTree sut = new InspectorTree(
                1, 1024L,
                new DType.Struct(List.of("v"), List.of(DType.I32), false),
                List.of("vortex.flat"), Set.of(),
                List.of(), 1000L, rootN);

        // When
        String result = VortexInspector.render(sut);

        // Then
        assertThat(result).contains("vortex.stats(1000 rows) → vortex.chunked(1000 rows) → vortex.flat(1000 rows)");
    }

    @Test
    void render_aggregatesMinMaxAcrossChunks() {
        // Given — column with two chunked Flat leaves; aggregate should fold each leaf's stats
        Layout chunk1 = new Layout(LayoutId.parse("vortex.flat"), 500, null, List.of(), List.of());
        Layout chunk2 = new Layout(LayoutId.parse("vortex.flat"), 500, null, List.of(), List.of());
        Layout chunked = new Layout(LayoutId.parse("vortex.chunked"), 1000, null, List.of(chunk1, chunk2), List.of());
        Layout structLayout = new Layout(LayoutId.parse("vortex.struct"), 1000, null, List.of(chunked), List.of());

        InspectorTree.Node c1 = new InspectorTree.Node(chunk1, Optional.empty(), Set.of(),
                new ArrayStats(10L, 50L, null, null, null, null, null), List.of());
        InspectorTree.Node c2 = new InspectorTree.Node(chunk2, Optional.empty(), Set.of(),
                new ArrayStats(5L, 100L, null, null, null, null, null), List.of());
        InspectorTree.Node chunkedN = new InspectorTree.Node(chunked, Optional.of("id"),
                Set.of("vortex.flat"), ArrayStats.empty(), List.of(c1, c2));
        InspectorTree.Node rootN = new InspectorTree.Node(structLayout, Optional.empty(),
                Set.of("vortex.flat"), ArrayStats.empty(), List.of(chunkedN));

        InspectorTree sut = new InspectorTree(1, 1024L,
                new DType.Struct(List.of("id"), List.of(DType.I64), false),
                List.of("vortex.flat"), Set.of(), List.of(), 1000L, rootN);

        // When
        String result = VortexInspector.render(sut);

        // Then — min over (10, 5) = 5; max over (50, 100) = 100
        assertThat(result).contains("min=5 max=100");
    }

    @Test
    void render_columnWithoutStats_omitsMinMax() {
        // Given — default tree has ArrayStats.empty() on every node
        InspectorTree sut = struct2col(1, 100L, List.of(), Set.of());

        // When
        String result = VortexInspector.render(sut);

        // Then
        assertThat(result).doesNotContain("min=");
        assertThat(result).doesNotContain("max=");
    }

    @Test
    void render_emptyUsedEncodings_omitsBracketSuffix() {
        // Given — column with no resolved encodings should not emit "  []" noise
        InspectorTree sut = struct2col(1, 100L, List.of(), Set.of());

        // When
        String result = VortexInspector.render(sut);

        // Then
        assertThat(result).doesNotContain("  []");
    }

    private static InspectorTree struct2col(int version, long fileSize, List<SegmentSpec> specs, Set<String> usedById) {
        Layout idLeaf = new Layout(LayoutId.parse("fastlanes.bitpacked"), 1000, null, List.of(), List.of());
        Layout valLeaf = new Layout(LayoutId.parse("vortex.constant"), 1000, null, List.of(), List.of());
        Layout root = new Layout(LayoutId.parse("vortex.struct"), 1000, null, List.of(idLeaf, valLeaf), List.of());

        InspectorTree.Node idNode = new InspectorTree.Node(idLeaf,
                Optional.of("id"), Set.of("fastlanes.bitpacked"), ArrayStats.empty(), List.of());
        InspectorTree.Node valNode = new InspectorTree.Node(valLeaf,
                Optional.of("value"), Set.of("vortex.constant"), ArrayStats.empty(), List.of());
        InspectorTree.Node rootNode = new InspectorTree.Node(root,
                Optional.empty(), Set.of("fastlanes.bitpacked", "vortex.constant"),
                ArrayStats.empty(), List.of(idNode, valNode));

        DType dtype = new DType.Struct(
                List.of("id", "value"),
                List.of(DType.I64, DType.F64),
                false);

        return new InspectorTree(version, fileSize, dtype,
                List.of("vortex.flat", "fastlanes.bitpacked", "vortex.constant"),
                usedById, specs, 1000L, rootNode);
    }
}
