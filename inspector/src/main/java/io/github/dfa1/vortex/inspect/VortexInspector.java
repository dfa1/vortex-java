package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.SegmentSpec;
import io.github.dfa1.vortex.io.VortexHandle;

import java.util.List;

/// Produces a human-readable summary of a Vortex file's structure and encodings.
public final class VortexInspector {

    private VortexInspector() {
    }

    /// Builds a multi-line text report for the given file handle.
    ///
    /// @param handle open file handle
    /// @return formatted report
    public static String inspect(VortexHandle handle) {
        return render(InspectorTree.build(handle));
    }

    /// Builds a multi-line text report from a pre-built inspector tree.
    ///
    /// @param tree inspector tree
    /// @return formatted report
    public static String render(InspectorTree tree) {
        var sb = new StringBuilder();

        sb.append("Vortex v").append(tree.version())
                .append("  ").append(formatBytes(tree.fileSize()))
                .append("  ").append(tree.totalRowCount()).append(" rows").append('\n');
        sb.append('\n');

        sb.append("Schema:\n");
        appendSchema(sb, tree.dtype(), "  ");
        sb.append('\n');

        sb.append("Registered encodings: ").append(String.join(", ", tree.registeredEncodings())).append('\n');
        sb.append('\n');

        sb.append("Used encodings: ").append(String.join(", ", tree.usedEncodings())).append('\n');
        sb.append('\n');

        sb.append("Segments: ").append(tree.segmentCount())
                .append("  total ").append(formatBytes(tree.totalSegmentBytes())).append('\n');
        appendSegmentTable(sb, tree.segmentSpecs(), "  ");
        sb.append('\n');

        sb.append("Layout:\n");
        appendLayout(sb, tree.root(), "  ");

        return sb.toString();
    }

    private static void appendSegmentTable(StringBuilder sb, List<SegmentSpec> specs, String indent) {
        for (int i = 0; i < specs.size(); i++) {
            SegmentSpec spec = specs.get(i);
            sb.append(indent).append('[').append(i).append("] ")
                    .append("off=").append(spec.offset())
                    .append("  len=").append(formatBytes(spec.length()))
                    .append("  comp=").append(spec.compression().name())
                    .append('\n');
        }
    }

    private static void appendLayout(StringBuilder sb, InspectorTree.Node node, String indent) {
        Layout layout = node.layout();
        if (layout.isStruct()) {
            sb.append(indent).append("struct (").append(layout.rowCount()).append(" rows)\n");
            for (InspectorTree.Node child : node.children()) {
                String name = child.fieldName().orElse("?");
                sb.append(indent).append("  ").append(name).append(": ");
                appendLayoutInline(sb, child.layout());
                if (!child.usedEncodings().isEmpty()) {
                    sb.append("  [").append(String.join(", ", child.usedEncodings())).append("]");
                }
                sb.append('\n');
            }
        } else {
            sb.append(indent);
            appendLayoutInline(sb, layout);
            sb.append('\n');
        }
    }

    private static void appendLayoutInline(StringBuilder sb, Layout layout) {
        sb.append(layout.encodingId()).append('(').append(layout.rowCount()).append(" rows)");
        if (layout.children().isEmpty()) {
            return;
        }
        sb.append(" → ");
        if (layout.children().size() == 1) {
            appendLayoutInline(sb, layout.children().getFirst());
        } else {
            sb.append(layout.children().size()).append("× [");
            appendLayoutInline(sb, layout.children().getFirst());
            sb.append("]");
        }
    }

    private static void appendSchema(StringBuilder sb, DType dtype, String indent) {
        if (dtype instanceof DType.Struct s) {
            int maxLen = s.fieldNames().stream().mapToInt(String::length).max().orElse(0);
            for (int i = 0; i < s.fieldNames().size(); i++) {
                String name = s.fieldNames().get(i);
                sb.append(indent).append(name)
                        .append(" ".repeat(maxLen - name.length() + 1))
                        .append(formatDType(s.fieldTypes().get(i))).append('\n');
            }
        } else {
            sb.append(indent).append(formatDType(dtype)).append('\n');
        }
    }

    private static String formatDType(DType dtype) {
        return switch (dtype) {
            case DType.Primitive(var pt, var nullable) -> pt.name() + (nullable ? "?" : "");
            case DType.Utf8(var nullable) -> "utf8" + (nullable ? "?" : "");
            case DType.Binary(var nullable) -> "binary" + (nullable ? "?" : "");
            case DType.Bool(var nullable) -> "bool" + (nullable ? "?" : "");
            case DType.Null ignored -> "null";
            case DType.Decimal(var p, var s, var nullable) -> "decimal(" + p + "," + s + ")" + (nullable ? "?" : "");
            case DType.Struct ignored -> "struct";
            case DType.List(var elem, var nullable) -> "list<" + formatDType(elem) + ">" + (nullable ? "?" : "");
            case DType.FixedSizeList(var elem, var size, var nullable) ->
                    "list<" + formatDType(elem) + ">[" + size + "]" + (nullable ? "?" : "");
            case DType.Extension(var id, var storage, var meta, var nullable) ->
                    "ext<" + id + ">" + (nullable ? "?" : "");
            case DType.Variant(var nullable) -> "variant" + (nullable ? "?" : "");
        };
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
