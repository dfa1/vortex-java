package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.layout.Layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/// Renders an [InspectorTree] as one self-contained HTML page - no scripts, no external
/// stylesheets, no fonts to fetch. Write the string to a `.html` file and open it.
///
/// The page shows what the text report cannot: where a column's bytes physically sit in the
/// file (a byte-accurate strip across the whole file, colored per column), what share of the
/// file each column costs, and the per-chunk row ranges, min/max and sizes behind those
/// totals.
///
/// Every value interpolated into the page comes from an untrusted file, so all of it goes
/// through [#escape(String)].
public final class HtmlReport {

    /// Categorical hues, light mode. Assigned to columns in fixed order and never cycled -
    /// column nine and beyond share the neutral `--other` fill and are told apart by name in
    /// the schema table instead.
    private static final List<String> LIGHT_SERIES = List.of(
            "#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948");

    /// The same eight hues stepped for the dark surface.
    private static final List<String> DARK_SERIES = List.of(
            "#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300", "#9085e9", "#e66767");

    /// Upper bound on blocks drawn in the file strip. A file with more segments than this has
    /// consecutive runs merged into one block each, so the strip stays a fixed-cost render
    /// instead of growing a DOM node per segment.
    // ponytail: fixed cap, merge runs; switch to a canvas or SVG strip only if per-segment
    // hover detail on a 100k-segment file is ever actually wanted.
    private static final int MAX_STRIP_BLOCKS = 2000;

    /// Longest rendered form of a min/max scalar before it is elided.
    private static final int MAX_VALUE_CHARS = 28;

    private HtmlReport() {
    }

    /// Renders the whole report.
    ///
    /// @param tree  inspector tree to render
    /// @param title file name shown in the header; escaped before it reaches the page
    /// @return a complete HTML document
    public static String render(InspectorTree tree, String title) {
        List<ColumnView> columns = columns(tree);
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(escape(title)).append(" - Vortex inspector</title>\n")
                .append("<style>\n").append(css()).append("</style>\n</head>\n<body>\n<main>\n");
        appendHeader(sb, tree, columns, title);
        appendStrip(sb, tree, columns);
        sb.append("<div class=\"panels\">\n");
        appendSchema(sb, tree, columns);
        appendChunks(sb, columns.stream().anyMatch(ColumnView::attributed)
                ? columns
                : List.of(wholeFile(tree)));
        sb.append("</div>\n</main>\n</body>\n</html>\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- sections

    private static void appendHeader(StringBuilder sb, InspectorTree tree, List<ColumnView> columns, String title) {
        sb.append("<section class=\"card\">\n<h1><code>").append(escape(title))
                .append("</code> <span class=\"muted\">Vortex v").append(tree.version())
                .append("</span></h1>\n<dl class=\"stats\">\n");
        stat(sb, "Size", ByteSize.format(tree.fileSize()));
        stat(sb, "Rows", count(tree.totalRowCount()));
        stat(sb, "Columns", count(columns.size()));
        stat(sb, "Chunks", count(Math.max(chunkCount(columns), chunks(tree.root(), tree.segmentSpecs()).size())));
        stat(sb, "Segments", count(tree.segmentCount()));
        stat(sb, "Metadata", ByteSize.format(metadataBytes(tree)));
        sb.append("</dl>\n<div class=\"badges\">\n");
        badge(sb, "Zone maps", anyLayout(tree.root(), Layout::isZoned));
        badge(sb, "Chunked", anyLayout(tree.root(), Layout::isChunked));
        badge(sb, "Dictionary", anyLayout(tree.root(), Layout::isDict));
        badge(sb, "Compressed segments",
                tree.segmentSpecs().stream().anyMatch(spec -> spec.compression().code != 0));
        sb.append("</div>\n</section>\n");
    }

    private static void appendStrip(StringBuilder sb, InspectorTree tree, List<ColumnView> columns) {
        List<SegmentSpec> specs = tree.segmentSpecs();
        long dataEnd = dataEnd(tree);
        long metadata = metadataBytes(tree);

        sb.append("<section class=\"card\">\n<div class=\"panelhead\">File")
                .append("<span class=\"muted\">").append(count(tree.segmentCount()))
                .append(" segments, byte-accurate</span><span class=\"right\">")
                .append(ByteSize.format(tree.fileSize())).append("</span></div>\n")
                .append("<div class=\"strips\">\n<div class=\"strip\">\n");
        // The data strip is scaled to the last segment's end rather than the file size: metadata
        // is a rounding error next to the columns (a few KB against megabytes), so sharing one
        // scale collapses it to a sliver. It gets its own strip below, at its own scale.
        int[] owner = segmentOwners(tree, columns);
        int stride = Math.max(1, (specs.size() + MAX_STRIP_BLOCKS - 1) / MAX_STRIP_BLOCKS);
        for (int i = 0; i < specs.size(); i += stride) {
            int last = Math.min(i + stride, specs.size()) - 1;
            long begin = specs.get(i).offset();
            long end = specs.get(last).offset() + specs.get(last).length();
            String label = stride == 1 ? "segment " + i : "segments " + i + "-" + last;
            String name = owner[i] < 0 ? "shared" : columns.get(owner[i]).name();
            block(sb, dataEnd, begin, end - begin, fill(owner[i]),
                    label + " \u00b7 " + name + " \u00b7 off " + count(begin) + " \u00b7 "
                            + ByteSize.format(end - begin) + " \u00b7 " + specs.get(i).compression().name());
        }
        sb.append("</div>\n");
        if (metadata > 0) {
            sb.append("<div class=\"strip meta\">\n");
            block(sb, metadata, 0, metadata, "var(--meta)",
                    "metadata \u00b7 footer, dtype, layout, postscript, trailer \u00b7 off "
                            + count(dataEnd) + " \u00b7 " + ByteSize.format(metadata));
            sb.append("</div>\n");
        }
        sb.append("</div>\n<div class=\"strips caps\">\n<span class=\"cap\">")
                .append(ByteSize.format(dataEnd)).append(" of column data</span>\n");
        if (metadata > 0) {
            sb.append("<span class=\"cap\">").append(ByteSize.format(metadata))
                    .append(" metadata, magnified</span>\n");
        }
        sb.append("</div>\n<div class=\"legend\">\n");
        for (ColumnView column : columns) {
            if (column.index() < LIGHT_SERIES.size()) {
                sb.append("<span class=\"key\"><i style=\"background:").append(fill(column.index()))
                        .append("\"></i><code>").append(escape(column.name())).append("</code> ")
                        .append(ByteSize.format(column.bytes())).append("</span>\n");
            }
        }
        int folded = columns.size() - LIGHT_SERIES.size();
        if (folded > 0) {
            sb.append("<span class=\"key\"><i style=\"background:var(--other)\"></i>other (")
                    .append(count(folded)).append(" columns)</span>\n");
        }
        if (metadata > 0) {
            sb.append("<span class=\"key\"><i style=\"background:var(--meta)\"></i>metadata ")
                    .append(ByteSize.format(metadata)).append("</span>\n");
        }
        sb.append("</div>\n<p class=\"hint\">Hover a block for its segment, column and byte range.")
                .append(" Gaps are alignment padding.</p>\n</section>\n");
    }

    private static void appendSchema(StringBuilder sb, InspectorTree tree, List<ColumnView> columns) {
        sb.append("<section class=\"card\">\n<div class=\"panelhead\">Schema<span class=\"muted\">")
                .append(count(columns.size())).append(" columns, size on disk</span></div>\n");
        long widest = columns.stream().mapToLong(ColumnView::bytes).max().orElse(1L);
        for (ColumnView column : columns) {
            appendColumn(sb, tree, column, widest);
        }
        sb.append("</section>\n");
    }

    private static void appendColumn(StringBuilder sb, InspectorTree tree, ColumnView column, long widest) {
        sb.append("<details class=\"row\">\n<summary><span class=\"ord\">")
                .append(column.index() + 1).append("</span><i class=\"dot\" style=\"background:")
                .append(fill(column.index())).append("\"></i><code class=\"name\">")
                .append(escape(column.name())).append("</code><code class=\"dtype\">")
                .append(escape(column.dtype())).append("</code>");
        if (column.attributed()) {
            bar(sb, column.bytes(), widest, fill(column.index()));
        }
        sb.append("<span class=\"size\">")
                .append(column.attributed() ? ByteSize.format(column.bytes()) : "shared")
                .append("</span></summary>\n<div class=\"detail\">\n<p class=\"meta\">");
        if (!column.attributed()) {
            sb.append("This file keeps every column in one layout node, so its bytes cannot be")
                    .append(" attributed to a single column. See the chunks panel for the file as a whole.")
                    .append("</p>\n</div>\n</details>\n");
            return;
        }
        sb.append(ByteSize.format(column.bytes())).append(" (")
                .append(percent(column.bytes(), tree.fileSize())).append(" of file), ")
                .append(count(column.chunks().size())).append(" chunks");
        if (!column.encodings().isEmpty()) {
            sb.append(", ").append(escape(String.join(", ", column.encodings())));
        }
        sb.append("</p>\n<table>\n<thead><tr><th>chunk</th><th>rows</th><th>min</th><th>max</th>")
                .append("<th>nulls</th><th>size</th></tr></thead>\n<tbody>\n");
        long widestChunk = column.chunks().stream().mapToLong(ChunkView::bytes).max().orElse(1L);
        for (int i = 0; i < column.chunks().size(); i++) {
            ChunkView chunk = column.chunks().get(i);
            Long nulls = chunk.stats().nullCount();
            sb.append("<tr><td>").append(i).append("</td><td class=\"num\">")
                    .append(rowRange(chunk)).append("</td><td>").append(value(chunk.stats().min()))
                    .append("</td><td>").append(value(chunk.stats().max())).append("</td><td class=\"num\">")
                    .append(nulls == null ? "<span class=\"muted\">-</span>" : count(nulls))
                    .append("</td><td class=\"num sized\">");
            bar(sb, chunk.bytes(), widestChunk, fill(column.index()));
            sb.append(ByteSize.format(chunk.bytes())).append("</td></tr>\n");
        }
        sb.append("</tbody>\n</table>\n</div>\n</details>\n");
    }

    private static void appendChunks(StringBuilder sb, List<ColumnView> columns) {
        int chunks = chunkCount(columns);
        sb.append("<section class=\"card\">\n<div class=\"panelhead\">Chunks<span class=\"muted\">")
                .append(count(chunks)).append(", column mix per chunk</span></div>\n");
        long widest = 1L;
        for (int i = 0; i < chunks; i++) {
            widest = Math.max(widest, chunkBytes(columns, i));
        }
        for (int i = 0; i < chunks; i++) {
            appendChunk(sb, columns, i, widest);
        }
        sb.append("</section>\n");
    }

    private static void appendChunk(StringBuilder sb, List<ColumnView> columns, int index, long widest) {
        long bytes = chunkBytes(columns, index);
        sb.append("<details class=\"row\">\n<summary><span class=\"ord\">").append(index)
                .append("</span><span class=\"rows\">").append(chunkRowRange(columns, index))
                .append("</span><span class=\"bar stack\">");
        for (ColumnView column : columns) {
            if (index < column.chunks().size()) {
                sb.append("<i style=\"width:").append(width(column.chunks().get(index).bytes(), widest))
                        .append(";background:").append(fill(column.index())).append("\"></i>");
            }
        }
        sb.append("</span><span class=\"size\">").append(ByteSize.format(bytes))
                .append("</span></summary>\n<div class=\"detail\">\n<table>\n")
                .append("<thead><tr><th>column</th><th>min</th><th>max</th><th>size</th></tr></thead>\n<tbody>\n");
        for (ColumnView column : columns) {
            if (index < column.chunks().size()) {
                ChunkView chunk = column.chunks().get(index);
                sb.append("<tr><td><i class=\"dot\" style=\"background:").append(fill(column.index()))
                        .append("\"></i><code>").append(escape(column.name())).append("</code></td><td>")
                        .append(value(chunk.stats().min())).append("</td><td>")
                        .append(value(chunk.stats().max())).append("</td><td class=\"num sized\">");
                bar(sb, chunk.bytes(), bytes, fill(column.index()));
                sb.append(ByteSize.format(chunk.bytes())).append("</td></tr>\n");
            }
        }
        sb.append("</tbody>\n</table>\n</div>\n</details>\n");
    }

    // ---------------------------------------------------------------- model

    /// One top-level column: its slot in the palette, name, rendered dtype, bytes on disk,
    /// encodings seen anywhere in its subtree, and its chunks left to right.
    private record ColumnView(int index, String name, String dtype, long bytes, boolean attributed,
            Set<String> encodings, List<ChunkView> chunks) {
    }

    /// One chunk of one column.
    private record ChunkView(long firstRow, long rows, long bytes, ArrayStats stats) {
    }

    /// Builds one view per column *of the schema*. Names and types always come from the dtype,
    /// which is authoritative; the layout is consulted only for physical facts (bytes, chunks),
    /// and only when its top level lines up one-for-one with the schema's fields.
    private static List<ColumnView> columns(InspectorTree tree) {
        List<String> names = fieldNames(tree);
        List<DType> types = tree.dtype() instanceof DType.Struct struct
                ? struct.fieldTypes()
                : List.of(tree.dtype());
        List<InspectorTree.Node> nodes = topLevel(tree);
        boolean attributed = nodes.size() == names.size();
        List<ColumnView> columns = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            InspectorTree.Node node = attributed ? nodes.get(i) : null;
            String dtype = i < types.size() ? VortexInspector.formatDType(types.get(i)) : "?";
            columns.add(new ColumnView(
                    i,
                    names.get(i),
                    dtype,
                    node == null ? 0L : subtreeBytes(node, tree.segmentSpecs()),
                    node != null,
                    node == null ? Set.<String>of() : node.usedEncodings(),
                    node == null ? List.<ChunkView>of() : chunks(node, tree.segmentSpecs())));
        }
        return columns;
    }

    private static List<String> fieldNames(InspectorTree tree) {
        if (tree.dtype() instanceof DType.Struct struct) {
            return struct.fieldNames().stream().map(ColumnName::value).toList();
        }
        return List.of(tree.root().fieldName().orElse("value"));
    }

    /// One pseudo-column standing for the whole file, used to drive the chunks panel when the
    /// layout does not break the file down per column.
    private static ColumnView wholeFile(InspectorTree tree) {
        return new ColumnView(-1, "all columns", "", subtreeBytes(tree.root(), tree.segmentSpecs()),
                true, tree.root().usedEncodings(), chunks(tree.root(), tree.segmentSpecs()));
    }

    /// Byte offset one past the last segment - where the file's trailing metadata begins.
    ///
    /// @param tree inspector tree
    /// @return the end of the data region, or `0` for a file with no segments
    private static long dataEnd(InspectorTree tree) {
        return tree.segmentSpecs().stream().mapToLong(spec -> spec.offset() + spec.length()).max().orElse(0L);
    }

    /// Bytes after the last segment: the footer, dtype and layout blobs, the postscript and the
    /// trailer. Measured from the last segment's end rather than from the sum of segment lengths,
    /// because the difference between those two is inter-segment alignment padding - real bytes,
    /// but not metadata.
    ///
    /// @param tree inspector tree
    /// @return size of the trailing metadata region in bytes
    private static long metadataBytes(InspectorTree tree) {
        return Math.max(0, tree.fileSize() - dataEnd(tree));
    }

    /// The layout nodes that correspond one-for-one to the schema's top-level fields, or an empty
    /// list when the layout does not break the file down that way. A struct dtype stored under a
    /// single flat layout node - which is what the Rust writer produces - keeps every column's
    /// bytes in one segment, so no column owns any of them.
    private static List<InspectorTree.Node> topLevel(InspectorTree tree) {
        if (tree.root().layout().isStruct()) {
            return tree.root().children();
        }
        return tree.dtype() instanceof DType.Struct ? List.of() : List.of(tree.root());
    }

    /// Descends from a column to the chunked node underneath and turns its children into chunks.
    /// A column with no chunked node below it has exactly one chunk.
    private static List<ChunkView> chunks(InspectorTree.Node column, List<SegmentSpec> specs) {
        InspectorTree.Node node = column;
        InspectorTree.Node next = dataChild(node);
        while (!node.layout().isChunked() && next != null) {
            node = next;
            next = dataChild(node);
        }
        List<InspectorTree.Node> parts = node.layout().isChunked() ? node.children() : List.of(node);
        List<ChunkView> chunks = new ArrayList<>(parts.size());
        long firstRow = 0;
        for (InspectorTree.Node part : parts) {
            long rows = part.layout().rowCount();
            chunks.add(new ChunkView(firstRow, rows, subtreeBytes(part, specs),
                    VortexInspector.aggregateStats(part)));
            firstRow += rows;
        }
        return chunks;
    }

    /// The child carrying this node's rows, or `null` when the node is a leaf or a shape whose
    /// rows do not live in one child. Mirrors the reader's own layout decoders: a [Layout#isZoned()]
    /// node wraps its data as `child[0]` and its zone-map table as `child[1]`, while a
    /// [Layout#isDict()] node stores `(values, codes)` and the codes are what carries the rows.
    /// Counting children alone gets both wrong.
    private static InspectorTree.Node dataChild(InspectorTree.Node node) {
        List<InspectorTree.Node> children = node.children();
        if (node.layout().isZoned() && !children.isEmpty()) {
            return children.getFirst();
        }
        if (node.layout().isDict() && children.size() >= 2) {
            return children.get(1);
        }
        return children.size() == 1 ? children.getFirst() : null;
    }

    private static long subtreeBytes(InspectorTree.Node node, List<SegmentSpec> specs) {
        long total = 0;
        for (int index : node.layout().segments()) {
            // Segment indices come off the wire; a malformed file can point anywhere.
            if (index >= 0 && index < specs.size()) {
                total += specs.get(index).length();
            }
        }
        for (InspectorTree.Node child : node.children()) {
            total += subtreeBytes(child, specs);
        }
        return total;
    }

    /// Maps each segment index to the column that owns it, or `-1` when no single column does
    /// (a segment held by the struct node itself, or one no layout node references).
    private static int[] segmentOwners(InspectorTree tree, List<ColumnView> columns) {
        int[] owner = new int[tree.segmentSpecs().size()];
        Arrays.fill(owner, -1);
        List<InspectorTree.Node> nodes = topLevel(tree);
        if (nodes.size() == columns.size()) {
            for (int i = 0; i < nodes.size(); i++) {
                claim(nodes.get(i), i, owner);
            }
        }
        return owner;
    }

    private static void claim(InspectorTree.Node node, int column, int[] owner) {
        for (int index : node.layout().segments()) {
            if (index >= 0 && index < owner.length) {
                owner[index] = column;
            }
        }
        for (InspectorTree.Node child : node.children()) {
            claim(child, column, owner);
        }
    }

    private static boolean anyLayout(InspectorTree.Node node, Predicate<Layout> test) {
        return test.test(node.layout())
                || node.children().stream().anyMatch(child -> anyLayout(child, test));
    }

    private static int chunkCount(List<ColumnView> columns) {
        return columns.stream().mapToInt(column -> column.chunks().size()).max().orElse(0);
    }

    private static long chunkBytes(List<ColumnView> columns, int chunk) {
        long total = 0;
        for (ColumnView column : columns) {
            if (chunk < column.chunks().size()) {
                total += column.chunks().get(chunk).bytes();
            }
        }
        return total;
    }

    private static String chunkRowRange(List<ColumnView> columns, int chunk) {
        for (ColumnView column : columns) {
            if (chunk < column.chunks().size()) {
                return rowRange(column.chunks().get(chunk));
            }
        }
        return "-";
    }

    // ---------------------------------------------------------------- fragments

    private static void stat(StringBuilder sb, String label, String value) {
        sb.append("<div><dt>").append(label).append("</dt><dd>").append(value).append("</dd></div>\n");
    }

    private static void badge(StringBuilder sb, String label, boolean present) {
        sb.append("<span class=\"badge").append(present ? " on" : "").append("\">")
                .append(present ? "✓ " : "– ").append(label).append("</span>\n");
    }

    private static void block(StringBuilder sb, long fileSize, long offset, long length, String fill, String tip) {
        sb.append("<i style=\"left:").append(width(offset, fileSize))
                .append(";width:").append(width(length, fileSize))
                .append(";background:").append(fill).append("\" title=\"").append(escape(tip)).append("\"></i>\n");
    }

    private static void bar(StringBuilder sb, long value, long max, String fill) {
        sb.append("<span class=\"bar\"><i style=\"width:").append(width(value, max))
                .append(";background:").append(fill).append("\"></i></span>");
    }

    private static String fill(int column) {
        return column < 0 || column >= LIGHT_SERIES.size()
                ? "var(--other)"
                : "var(--series-" + (column + 1) + ")";
    }

    private static String width(long value, long total) {
        double pct = total <= 0 ? 0.0 : 100.0 * value / total;
        return String.format(Locale.ROOT, "%.4f%%", pct);
    }

    private static String percent(long value, long total) {
        return total <= 0 ? "0%" : String.format(Locale.ROOT, "%.1f%%", 100.0 * value / total);
    }

    private static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String rowRange(ChunkView chunk) {
        if (chunk.rows() <= 0) {
            return "empty";
        }
        return count(chunk.firstRow()) + "-" + count(chunk.firstRow() + chunk.rows() - 1);
    }

    private static String value(Object raw) {
        if (raw == null) {
            return "<span class=\"muted\">-</span>";
        }
        String text = raw.toString();
        if (text.length() > MAX_VALUE_CHARS) {
            text = text.substring(0, MAX_VALUE_CHARS - 1) + "…";
        }
        return "<code>" + escape(text) + "</code>";
    }

    /// Escapes text for interpolation into element content or a double-quoted attribute.
    /// Everything on this page originates in an untrusted file, so nothing skips this.
    ///
    /// @param raw text as read from the file
    /// @return the same text with HTML-significant characters replaced by entities
    static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String css() {
        return CSS.formatted(String.join("\n  ", vars(LIGHT_SERIES)), String.join("\n    ", vars(DARK_SERIES)));
    }

    private static List<String> vars(List<String> series) {
        List<String> out = new ArrayList<>(series.size());
        for (int i = 0; i < series.size(); i++) {
            out.add("--series-" + (i + 1) + ": " + series.get(i) + ";");
        }
        return out;
    }

    private static final String CSS = """
            :root {
              color-scheme: light;
              --surface-0: #f4f4f2;
              --surface-1: #fcfcfb;
              --border: #e3e2dd;
              --text-primary: #0b0b0b;
              --text-secondary: #52514e;
              --text-muted: #8b8a82;
              --track: #ecebe7;
              --other: #b6b5ae;
              --meta: #52514e;
              %s
            }
            @media (prefers-color-scheme: dark) {
              :root:not([data-theme="light"]) {
                color-scheme: dark;
                --surface-0: #111110;
                --surface-1: #1a1a19;
                --border: #34332f;
                --text-primary: #ffffff;
                --text-secondary: #c3c2b7;
                --text-muted: #8d8c82;
                --track: #2a2926;
                --other: #6b6a63;
                --meta: #9a998f;
                %s
              }
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              padding: 24px 16px 64px;
              background: var(--surface-0);
              color: var(--text-primary);
              font: 14px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
            }
            main { max-width: 1180px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
            code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 0.92em; }
            .card { background: var(--surface-1); border: 1px solid var(--border); border-radius: 10px; padding: 16px 18px; }
            .muted { color: var(--text-muted); font-weight: 400; }
            h1 { font-size: 17px; margin: 0 0 14px; display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; }
            h1 code { font-size: 17px; }
            .stats { display: flex; flex-wrap: wrap; gap: 10px 36px; margin: 0; }
            .stats dt { color: var(--text-secondary); font-size: 12px; }
            .stats dd { margin: 2px 0 0; font-size: 17px; font-weight: 600; font-variant-numeric: tabular-nums; }
            .badges { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 16px; }
            .badge { border: 1px solid var(--border); border-radius: 999px; padding: 3px 11px; font-size: 12px; color: var(--text-muted); }
            .badge.on { color: #0ca30c; border-color: #0ca30c66; }
            .panelhead { display: flex; align-items: baseline; gap: 10px; font-weight: 600; margin-bottom: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--border); }
            .panelhead .muted { font-size: 12px; font-weight: 400; }
            .panelhead .right { margin-left: auto; color: var(--text-muted); font-weight: 400; font-size: 12px; }
            .strips { display: flex; gap: 10px; align-items: stretch; }
            .strips > * { flex: 1 1 auto; min-width: 0; }
            .strips > .meta, .strips > .cap:last-child:not(:only-child) { flex: 0 0 104px; }
            .caps { margin-top: 6px; font-size: 11px; color: var(--text-muted); }
            .strip { position: relative; height: 46px; background: var(--track); border-radius: 5px; overflow: hidden; }
            .strip i { position: absolute; top: 0; bottom: 0; min-width: 2px; border-right: 1px solid var(--surface-1); }
            .legend { display: flex; flex-wrap: wrap; gap: 6px 18px; margin-top: 12px; font-size: 12px; color: var(--text-secondary); }
            .key { display: inline-flex; align-items: center; gap: 6px; }
            .key i, .dot { width: 10px; height: 10px; border-radius: 3px; display: inline-block; flex: none; }
            .dot { margin-right: 7px; vertical-align: -1px; }
            .hint { margin: 10px 0 0; font-size: 12px; color: var(--text-muted); }
            .panels { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
            @media (max-width: 900px) { .panels { grid-template-columns: 1fr; } }
            .row { border-bottom: 1px solid var(--border); }
            .row:last-of-type { border-bottom: 0; }
            .row > summary { display: flex; align-items: center; gap: 10px; padding: 7px 4px; cursor: pointer; list-style: none; }
            .row > summary::-webkit-details-marker { display: none; }
            .row > summary:hover, .row[open] > summary { background: var(--surface-0); }
            .ord { width: 22px; color: var(--text-muted); font-size: 12px; font-variant-numeric: tabular-nums; }
            .name { font-weight: 600; }
            .dtype, .rows { color: var(--text-secondary); font-size: 12px; font-variant-numeric: tabular-nums; }
            .bar { margin-left: auto; width: 34%%; height: 8px; background: var(--track); border-radius: 4px; overflow: hidden; display: flex; flex: none; }
            .bar i { display: block; height: 100%%; border-radius: 0 4px 4px 0; }
            .bar.stack i { border-radius: 0; border-right: 1px solid var(--surface-1); }
            .size { width: 74px; text-align: right; font-size: 12px; font-variant-numeric: tabular-nums; color: var(--text-secondary); }
            .detail { padding: 4px 4px 16px 26px; }
            .meta { margin: 0 0 10px; font-size: 12px; color: var(--text-muted); }
            table { width: 100%%; border-collapse: collapse; font-size: 12px; }
            th { text-align: left; font-weight: 500; color: var(--text-muted); padding: 4px 8px 4px 0; border-bottom: 1px solid var(--border); }
            td { padding: 4px 8px 4px 0; border-bottom: 1px solid var(--border); vertical-align: middle; }
            tr:last-child td { border-bottom: 0; }
            .num { font-variant-numeric: tabular-nums; }
            .sized { text-align: right; white-space: nowrap; }
            .sized .bar { width: 46px; display: inline-flex; margin: 0 8px 0 0; vertical-align: 1px; }
            """;
}
