package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.CompressionScheme;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.layout.Layout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlReportTest {

    @Test
    void render_emitsSelfContainedDocument() {
        // Given
        InspectorTree sut = twoColumnTree();

        // When
        String result = HtmlReport.render(sut, "data.vortex");

        // Then — no network fetches: the page must carry its own styles and pull in nothing else
        assertThat(result)
                .startsWith("<!doctype html>")
                .endsWith("</html>\n")
                .contains("<title>data.vortex - Vortex inspector</title>")
                .contains("<style>")
                .doesNotContain("<script")
                .doesNotContain("http://")
                .doesNotContain("https://");
    }

    @Test
    void render_headerCarriesFileTotals() {
        // Given
        InspectorTree sut = twoColumnTree();

        // When
        String result = HtmlReport.render(sut, "data.vortex");

        // Then — 4096-byte file, 2048 bytes of segments, so 2048 bytes of trailing metadata
        assertThat(result)
                .contains("Vortex v2")
                .contains("<dd>4.0 KB</dd>")
                .contains("<dd>1,000</dd>")
                .contains("<dt>Metadata</dt><dd>2.0 KB</dd>");
    }

    @Test
    void render_assignsEachColumnItsOwnPaletteSlot() {
        // Given
        InspectorTree sut = twoColumnTree();

        // When
        String result = HtmlReport.render(sut, "data.vortex");

        // Then — fixed-order slots, never cycled, so two columns take slots 1 and 2
        assertThat(result)
                .contains("var(--series-1)")
                .contains("var(--series-2)")
                .doesNotContain("var(--series-3)");
    }

    @Test
    void render_sizesColumnsFromTheirOwnSegments() {
        // Given — id owns segment 0 (1 KB), value owns segment 1 (1 KB)
        InspectorTree sut = twoColumnTree();

        // When
        String result = HtmlReport.render(sut, "data.vortex");

        // Then — each column is half the file, and the widest bar is full width
        assertThat(result)
                .contains("1.0 KB (25.0% of file)")
                .contains("width:100.0000%");
    }

    @Test
    void render_escapesUntrustedColumnNames() {
        // Given — a column name is attacker-controlled: it comes straight off the wire
        InspectorTree sut = singleColumnNamed("<script>alert('x')</script>");

        // When
        String result = HtmlReport.render(sut, "evil.vortex");

        // Then — the tag must never survive into the page as markup
        assertThat(result)
                .doesNotContain("<script>")
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
    }

    @Test
    void render_escapesUntrustedStatValues() {
        // Given — min/max are decoded scalars from the file, so a string column can carry markup
        InspectorTree sut = singleColumnWithStats(new ArrayStats("<b>", "\"&\"", null, null, 3L, null, null));

        // When
        String result = HtmlReport.render(sut, "evil.vortex");

        // Then
        assertThat(result)
                .doesNotContain("<b>")
                .contains("&lt;b&gt;")
                .contains("&quot;&amp;&quot;");
    }

    @Test
    void render_zeroSizedFile_doesNotDivideByZero() {
        // Given — a degenerate tree; percentage math must not produce NaN or Infinity in CSS
        Layout leaf = new Layout(LayoutId.parse("vortex.flat"), 0, null, List.of(), List.of());
        InspectorTree.Node root = new InspectorTree.Node(leaf, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree sut = new InspectorTree(1, 0L, DType.I32, List.of(), Set.of(), List.of(), 0L, root);

        // When
        String result = HtmlReport.render(sut, "empty.vortex");

        // Then
        assertThat(result)
                .doesNotContain("NaN")
                .doesNotContain("Infinity")
                .contains("empty");
    }

    @Test
    void render_outOfRangeSegmentIndex_isIgnoredNotThrown() {
        // Given — a malformed file can point a layout at a segment that does not exist; the
        // reader's security contract says tooling must not blow up on it
        Layout leaf = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(7, -1));
        Layout structLayout = new Layout(LayoutId.parse("vortex.struct"), 10, null, List.of(leaf), List.of());
        InspectorTree.Node leafNode = new InspectorTree.Node(leaf, Optional.of("id"), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node root = new InspectorTree.Node(structLayout, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of(leafNode));
        InspectorTree sut = new InspectorTree(1, 128L,
                new DType.Struct(List.of(ColumnName.of("id")), List.of(DType.I32), false),
                List.of(), Set.of(),
                List.of(new SegmentSpec(0, 64, (byte) 0, CompressionScheme.NONE)),
                10L, root);

        // When
        String result = HtmlReport.render(sut, "bad.vortex");

        // Then — the bogus indices contribute nothing rather than throwing
        assertThat(result).contains("id").contains("0 B");
    }

    @Test
    void render_chunkedColumn_listsOneRowPerChunk() {
        // Given — two chunks of 500 rows each
        InspectorTree sut = chunkedTree();

        // When
        String result = HtmlReport.render(sut, "chunked.vortex");

        // Then — row ranges are cumulative across chunks, not per-chunk-local
        assertThat(result)
                .contains("0-499")
                .contains("500-999")
                .contains("<dt>Chunks</dt><dd>2</dd>");
    }

    @Test
    void render_zonedColumn_descendsPastTheZoneMapTableToTheChunks() {
        // Given — the real writer shape: Zoned wraps (data, zone-map table), so the zoned node has
        // TWO children. Counting children instead of following child[0] stops the descent here and
        // reports the whole column as a single chunk.
        Layout c0 = new Layout(LayoutId.parse("vortex.flat"), 500, null, List.of(), List.of(0));
        Layout c1 = new Layout(LayoutId.parse("vortex.flat"), 500, null, List.of(), List.of(1));
        Layout chunked = new Layout(LayoutId.parse("vortex.chunked"), 1000, null, List.of(c0, c1), List.of());
        Layout zoneTable = new Layout(LayoutId.parse("vortex.flat"), 2, null, List.of(), List.of(2));
        Layout zoned = new Layout(LayoutId.parse("vortex.stats"), 1000, null,
                List.of(chunked, zoneTable), List.of());
        Layout root = new Layout(LayoutId.parse("vortex.struct"), 1000, null, List.of(zoned), List.of());

        InspectorTree.Node n0 = new InspectorTree.Node(c0, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node n1 = new InspectorTree.Node(c1, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node chunkedNode = new InspectorTree.Node(chunked, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of(n0, n1));
        InspectorTree.Node tableNode = new InspectorTree.Node(zoneTable, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node col = new InspectorTree.Node(zoned, Optional.of("id"), Set.of(),
                ArrayStats.empty(), List.of(chunkedNode, tableNode));
        InspectorTree.Node rootNode = new InspectorTree.Node(root, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of(col));

        InspectorTree sut = new InspectorTree(2, 4096L,
                new DType.Struct(List.of(ColumnName.of("id")), List.of(DType.I64), false),
                List.of("vortex.flat"), Set.of(),
                List.of(new SegmentSpec(0, 1024, (byte) 0, CompressionScheme.NONE),
                        new SegmentSpec(1024, 1024, (byte) 0, CompressionScheme.NONE),
                        new SegmentSpec(2048, 64, (byte) 0, CompressionScheme.NONE)),
                1000L, rootNode);

        // When
        String result = HtmlReport.render(sut, "zoned.vortex");

        // Then
        assertThat(result)
                .contains("<dt>Chunks</dt><dd>2</dd>")
                .contains("0-499")
                .contains("500-999");
    }

    @Test
    void render_dictColumn_followsTheCodesChildNotTheValuePool() {
        // Given — a dict layout is (values, codes); the codes child is the one with the rows,
        // so a descent that takes child[0] lands in the value pool and loses the chunking.
        Layout c0 = new Layout(LayoutId.parse("vortex.flat"), 300, null, List.of(), List.of(1));
        Layout c1 = new Layout(LayoutId.parse("vortex.flat"), 300, null, List.of(), List.of(2));
        Layout codes = new Layout(LayoutId.parse("vortex.chunked"), 600, null, List.of(c0, c1), List.of());
        Layout values = new Layout(LayoutId.parse("vortex.flat"), 12, null, List.of(), List.of(0));
        Layout dict = new Layout(LayoutId.parse("vortex.dict"), 600, null, List.of(values, codes), List.of());
        Layout root = new Layout(LayoutId.parse("vortex.struct"), 600, null, List.of(dict), List.of());

        InspectorTree.Node n0 = new InspectorTree.Node(c0, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node n1 = new InspectorTree.Node(c1, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node codesNode = new InspectorTree.Node(codes, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of(n0, n1));
        InspectorTree.Node valuesNode = new InspectorTree.Node(values, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node col = new InspectorTree.Node(dict, Optional.of("city"), Set.of(),
                ArrayStats.empty(), List.of(valuesNode, codesNode));
        InspectorTree.Node rootNode = new InspectorTree.Node(root, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of(col));

        InspectorTree sut = new InspectorTree(2, 2048L,
                new DType.Struct(List.of(ColumnName.of("city")), List.of(new DType.Utf8(false)), false),
                List.of("vortex.flat"), Set.of(),
                List.of(new SegmentSpec(0, 64, (byte) 0, CompressionScheme.NONE),
                        new SegmentSpec(64, 512, (byte) 0, CompressionScheme.NONE),
                        new SegmentSpec(576, 512, (byte) 0, CompressionScheme.NONE)),
                600L, rootNode);

        // When
        String result = HtmlReport.render(sut, "dict.vortex");

        // Then
        assertThat(result)
                .contains("<dt>Chunks</dt><dd>2</dd>")
                .contains("0-299")
                .contains("300-599");
    }

    @Test
    void render_chunkBars_areScaledAgainstTheLargestChunk() {
        // Given — two chunks of very different size. Scaling each stack against its own total
        // would make both bars full width, so a small chunk would look as big as a large one.
        Layout c0 = new Layout(LayoutId.parse("vortex.flat"), 500, null, List.of(), List.of(0));
        Layout c1 = new Layout(LayoutId.parse("vortex.flat"), 500, null, List.of(), List.of(1));
        Layout chunked = new Layout(LayoutId.parse("vortex.chunked"), 1000, null, List.of(c0, c1), List.of());
        Layout root = new Layout(LayoutId.parse("vortex.struct"), 1000, null, List.of(chunked), List.of());

        InspectorTree.Node n0 = new InspectorTree.Node(c0, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node n1 = new InspectorTree.Node(c1, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node col = new InspectorTree.Node(chunked, Optional.of("id"), Set.of(),
                ArrayStats.empty(), List.of(n0, n1));
        InspectorTree.Node rootNode = new InspectorTree.Node(root, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of(col));

        InspectorTree sut = new InspectorTree(2, 8192L,
                new DType.Struct(List.of(ColumnName.of("id")), List.of(DType.I64), false),
                List.of("vortex.flat"), Set.of(),
                List.of(new SegmentSpec(0, 4096, (byte) 0, CompressionScheme.NONE),
                        new SegmentSpec(4096, 1024, (byte) 0, CompressionScheme.NONE)),
                1000L, rootNode);

        // When
        String result = HtmlReport.render(sut, "uneven.vortex");

        // Then — the 1 KB chunk is a quarter of the 4 KB one, not another full-width bar
        assertThat(result)
                .contains("width:100.0000%")
                .contains("width:25.0000%");
    }

    @Test
    void render_metadata_getsItsOwnStripAtItsOwnScale() {
        // Given — 2 KB of segments in a 4 KB file. Sharing one scale with the data would be fine
        // here, but on a real file metadata is a few KB against megabytes and collapses to a
        // sliver, so it is always drawn full-width in a strip of its own.
        InspectorTree sut = twoColumnTree();

        // When
        String result = HtmlReport.render(sut, "data.vortex");

        // Then
        assertThat(result)
                .contains("<div class=\"strip meta\">")
                .contains("var(--meta)")
                .contains("2.0 KB metadata, magnified")
                .contains("2.0 KB of column data");
    }

    @Test
    void render_noMetadataTail_omitsTheMagnifiedStrip() {
        // Given — segments run to the last byte, so there is no trailing metadata to show
        Layout leaf = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(0));
        Layout root = new Layout(LayoutId.parse("vortex.struct"), 10, null, List.of(leaf), List.of());
        InspectorTree.Node leafNode = new InspectorTree.Node(leaf, Optional.of("id"), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node rootNode = new InspectorTree.Node(root, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of(leafNode));
        InspectorTree sut = new InspectorTree(1, 256L,
                new DType.Struct(List.of(ColumnName.of("id")), List.of(DType.I32), false),
                List.of("vortex.flat"), Set.of(),
                List.of(new SegmentSpec(0, 256, (byte) 0, CompressionScheme.NONE)),
                10L, rootNode);

        // When
        String result = HtmlReport.render(sut, "full.vortex");

        // Then
        assertThat(result)
                .doesNotContain("strip meta")
                .doesNotContain("magnified");
    }

    @Test
    void render_metadataFigure_excludesInterSegmentPadding() {
        // Given — 2 KB of segments but a 1 KB gap between them, in a 4 KB file. Measuring
        // metadata as "file minus the sum of segment lengths" would call the padding metadata and
        // report 2 KB in the header while the strip, measuring from the last segment's end,
        // showed 1 KB. One page, two numbers, same label.
        Layout a = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(0));
        Layout b = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(1));
        Layout root = new Layout(LayoutId.parse("vortex.struct"), 10, null, List.of(a, b), List.of());
        InspectorTree.Node an = new InspectorTree.Node(a, Optional.of("x"), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node bn = new InspectorTree.Node(b, Optional.of("y"), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node rootNode = new InspectorTree.Node(root, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of(an, bn));
        InspectorTree sut = new InspectorTree(1, 4096L,
                new DType.Struct(List.of(ColumnName.of("x"), ColumnName.of("y")),
                        List.of(DType.I32, DType.I32), false),
                List.of("vortex.flat"), Set.of(),
                List.of(new SegmentSpec(0, 1024, (byte) 0, CompressionScheme.NONE),
                        new SegmentSpec(2048, 1024, (byte) 0, CompressionScheme.NONE)),
                10L, rootNode);

        // When
        String result = HtmlReport.render(sut, "padded.vortex");

        // Then — 4096 - 3072 = 1 KB, in both places
        assertThat(result)
                .contains("<dt>Metadata</dt><dd>1.0 KB</dd>")
                .contains("1.0 KB metadata, magnified");
    }

    @Test
    void render_structDtypeUnderFlatLayout_listsEveryFieldAndClaimsNoSizes() {
        // Given — the shape every Rust-written fixture has: a two-field struct dtype stored in ONE
        // flat layout node. Deriving the column list from the layout instead of the schema reported
        // a single column called "col0" typed with the FIRST field's type, silently dropping the
        // second field and mislabelling the first.
        Layout leaf = new Layout(LayoutId.parse("vortex.flat"), 1750, null, List.of(), List.of(0));
        InspectorTree.Node root = new InspectorTree.Node(leaf, Optional.empty(), Set.of("vortex.struct"),
                ArrayStats.empty(), List.of());
        InspectorTree sut = new InspectorTree(1, 24576L,
                new DType.Struct(List.of(ColumnName.of("id"), ColumnName.of("nullable_val")),
                        List.of(DType.U32, new DType.Primitive(PType.I64, true)), false),
                List.of("vortex.flat"), Set.of(),
                List.of(new SegmentSpec(0, 20480, (byte) 0, CompressionScheme.NONE)),
                1750L, root);

        // When
        String result = HtmlReport.render(sut, "chunked.vortex");

        // Then — both fields, both real types, and no invented per-column sizes
        assertThat(result)
                .contains("<dt>Columns</dt><dd>2</dd>")
                .contains(">id</code>")
                .contains(">nullable_val</code>")
                .contains("U32")
                .contains("I64?")
                .doesNotContain("col0")
                .contains("shared")
                .contains("cannot be attributed to a single column");
    }

    @Test
    void render_structDtypeUnderFlatLayout_stillReportsFileLevelChunks() {
        // Given — no column owns bytes, but the file is still chunked at the root; the chunks panel
        // falls back to the file as a whole rather than going blank
        Layout c0 = new Layout(LayoutId.parse("vortex.flat"), 500, null, List.of(), List.of(0));
        Layout c1 = new Layout(LayoutId.parse("vortex.flat"), 500, null, List.of(), List.of(1));
        Layout chunked = new Layout(LayoutId.parse("vortex.chunked"), 1000, null, List.of(c0, c1), List.of());
        InspectorTree.Node n0 = new InspectorTree.Node(c0, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node n1 = new InspectorTree.Node(c1, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of());
        InspectorTree.Node root = new InspectorTree.Node(chunked, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of(n0, n1));
        InspectorTree sut = new InspectorTree(1, 4096L,
                new DType.Struct(List.of(ColumnName.of("a"), ColumnName.of("b"), ColumnName.of("c")),
                        List.of(DType.I32, DType.I32, DType.I32), false),
                List.of("vortex.flat"), Set.of(),
                List.of(new SegmentSpec(0, 1024, (byte) 0, CompressionScheme.NONE),
                        new SegmentSpec(1024, 1024, (byte) 0, CompressionScheme.NONE)),
                1000L, root);

        // When
        String result = HtmlReport.render(sut, "shared.vortex");

        // Then
        assertThat(result)
                .contains("<dt>Columns</dt><dd>3</dd>")
                .contains("<dt>Chunks</dt><dd>2</dd>")
                .contains("all columns");
    }

    private static InspectorTree twoColumnTree() {
        Layout idLeaf = new Layout(LayoutId.parse("vortex.flat"), 1000, null, List.of(), List.of(0));
        Layout valLeaf = new Layout(LayoutId.parse("vortex.flat"), 1000, null, List.of(), List.of(1));
        Layout root = new Layout(LayoutId.parse("vortex.struct"), 1000, null, List.of(idLeaf, valLeaf), List.of());

        InspectorTree.Node idNode = new InspectorTree.Node(idLeaf, Optional.of("id"),
                Set.of("fastlanes.bitpacked"), new ArrayStats(1L, 999L, null, null, 0L, null, null), List.of());
        InspectorTree.Node valNode = new InspectorTree.Node(valLeaf, Optional.of("value"),
                Set.of("vortex.constant"), ArrayStats.empty(), List.of());
        InspectorTree.Node rootNode = new InspectorTree.Node(root, Optional.empty(),
                Set.of("fastlanes.bitpacked", "vortex.constant"), ArrayStats.empty(), List.of(idNode, valNode));

        return new InspectorTree(2, 4096L,
                new DType.Struct(List.of(ColumnName.of("id"), ColumnName.of("value")),
                        List.of(DType.I64, DType.F64), false),
                List.of("vortex.flat"), Set.of("fastlanes.bitpacked"),
                List.of(new SegmentSpec(0, 1024, (byte) 0, CompressionScheme.NONE),
                        new SegmentSpec(1024, 1024, (byte) 0, CompressionScheme.NONE)),
                1000L, rootNode);
    }

    private static InspectorTree chunkedTree() {
        Layout c0 = new Layout(LayoutId.parse("vortex.flat"), 500, null, List.of(), List.of(0));
        Layout c1 = new Layout(LayoutId.parse("vortex.flat"), 500, null, List.of(), List.of(1));
        Layout chunked = new Layout(LayoutId.parse("vortex.chunked"), 1000, null, List.of(c0, c1), List.of());
        Layout root = new Layout(LayoutId.parse("vortex.struct"), 1000, null, List.of(chunked), List.of());

        InspectorTree.Node n0 = new InspectorTree.Node(c0, Optional.empty(), Set.of(),
                new ArrayStats(0L, 499L, null, null, null, null, null), List.of());
        InspectorTree.Node n1 = new InspectorTree.Node(c1, Optional.empty(), Set.of(),
                new ArrayStats(500L, 999L, null, null, null, null, null), List.of());
        InspectorTree.Node col = new InspectorTree.Node(chunked, Optional.of("id"), Set.of(),
                ArrayStats.empty(), List.of(n0, n1));
        InspectorTree.Node rootNode = new InspectorTree.Node(root, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of(col));

        return new InspectorTree(2, 4096L,
                new DType.Struct(List.of(ColumnName.of("id")), List.of(DType.I64), false),
                List.of("vortex.flat"), Set.of(),
                List.of(new SegmentSpec(0, 1024, (byte) 0, CompressionScheme.NONE),
                        new SegmentSpec(1024, 1024, (byte) 0, CompressionScheme.NONE)),
                1000L, rootNode);
    }

    private static InspectorTree singleColumnNamed(String name) {
        return singleColumn(name, ArrayStats.empty());
    }

    private static InspectorTree singleColumnWithStats(ArrayStats stats) {
        return singleColumn("s", stats);
    }

    private static InspectorTree singleColumn(String name, ArrayStats stats) {
        Layout leaf = new Layout(LayoutId.parse("vortex.flat"), 10, null, List.of(), List.of(0));
        Layout root = new Layout(LayoutId.parse("vortex.struct"), 10, null, List.of(leaf), List.of());
        InspectorTree.Node leafNode = new InspectorTree.Node(leaf, Optional.of(name), Set.of(), stats, List.of());
        InspectorTree.Node rootNode = new InspectorTree.Node(root, Optional.empty(), Set.of(),
                ArrayStats.empty(), List.of(leafNode));
        return new InspectorTree(1, 256L,
                new DType.Struct(List.of(ColumnName.of(name)), List.of(new DType.Utf8(false)), false),
                List.of("vortex.flat"), Set.of(),
                List.of(new SegmentSpec(0, 128, (byte) 0, CompressionScheme.NONE)),
                10L, rootNode);
    }
}
