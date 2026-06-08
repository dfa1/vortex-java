package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.SegmentSpec;
import io.github.dfa1.vortex.inspect.term.Ansi;
import io.github.dfa1.vortex.inspect.term.Key;
import io.github.dfa1.vortex.inspect.term.RawTerminal;
import io.github.dfa1.vortex.io.VortexHandle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Interactive viewer for a Vortex file's inspector tree, drawn with raw ANSI
/// escapes — no library dependency.
///
/// Renders a two-pane terminal UI: layout tree on the left, node details on
/// the right. Quit with {@code q} or {@code Esc}.
public final class VortexInspectorTui {

    private VortexInspectorTui() {
    }

    /// Opens the terminal in raw mode, builds an inspector tree, and runs the
    /// interactive viewer until quit.
    ///
    /// @param handle open Vortex file handle
    /// @throws IOException if the terminal cannot be initialized
    public static void show(VortexHandle handle) throws IOException {
        show(handle, InspectorTree.Progress.NOOP);
    }

    /// Builds an inspector tree (reporting progress on each segment peek)
    /// and runs the interactive viewer until quit. Useful for remote files
    /// where {@link InspectorTree#build} can take seconds.
    ///
    /// @param handle   open Vortex file handle
    /// @param progress progress sink, called once per Flat segment peeked
    /// @throws IOException if the terminal cannot be initialized
    public static void show(VortexHandle handle, InspectorTree.Progress progress) throws IOException {
        InspectorTree tree = InspectorTree.build(handle, progress);
        try (RawTerminal term = RawTerminal.open()) {
            new Loop(term, tree).run();
        }
    }

    private static final class Loop {
        private final RawTerminal term;
        private final InspectorTree tree;
        private final Set<InspectorTree.Node> expanded = new HashSet<>();
        private int selected;
        private int scrollOffset;

        Loop(RawTerminal term, InspectorTree tree) {
            this.term = term;
            this.tree = tree;
            this.expanded.add(tree.root());
        }

        void run() throws IOException {
            while (true) {
                List<Item> items = flatten();
                if (selected >= items.size()) {
                    selected = items.size() - 1;
                }
                if (selected < 0) {
                    selected = 0;
                }
                render(items);
                Key key = term.readKey();
                if (isQuit(key)) {
                    return;
                }
                handleKey(key, items);
            }
        }

        private void handleKey(Key key, List<Item> items) {
            switch (key) {
                case Key.ArrowDown ignored -> selected = Math.min(selected + 1, items.size() - 1);
                case Key.ArrowUp ignored -> selected = Math.max(selected - 1, 0);
                case Key.ArrowRight ignored -> expandSelected(items);
                case Key.Enter ignored -> toggleSelected(items);
                case Key.ArrowLeft ignored -> {
                    if (selected < items.size()) {
                        expanded.remove(items.get(selected).node());
                    }
                }
                case Key.PageDown ignored -> selected = Math.min(selected + 10, items.size() - 1);
                case Key.PageUp ignored -> selected = Math.max(selected - 10, 0);
                case Key.Home ignored -> selected = 0;
                case Key.End ignored -> selected = items.size() - 1;
                default -> {
                }
            }
        }

        private void expandSelected(List<Item> items) {
            if (selected < items.size()) {
                InspectorTree.Node n = items.get(selected).node();
                if (!n.children().isEmpty()) {
                    expanded.add(n);
                }
            }
        }

        private void toggleSelected(List<Item> items) {
            if (selected >= items.size()) {
                return;
            }
            InspectorTree.Node n = items.get(selected).node();
            if (n.children().isEmpty()) {
                return;
            }
            if (!expanded.add(n)) {
                expanded.remove(n);
            }
        }

        private static boolean isQuit(Key key) {
            return key instanceof Key.Escape
                    || key instanceof Key.Eof
                    || (key instanceof Key.Char(char c) && (c == 'q' || c == 'Q'));
        }

        private List<Item> flatten() {
            List<Item> out = new ArrayList<>();
            walk(tree.root(), 0, out);
            return out;
        }

        private void walk(InspectorTree.Node node, int depth, List<Item> out) {
            out.add(new Item(node, depth));
            if (expanded.contains(node)) {
                for (InspectorTree.Node child : node.children()) {
                    walk(child, depth + 1, out);
                }
            }
        }

        private void render(List<Item> items) throws IOException {
            RawTerminal.Size size = term.size();
            int width = size.cols();
            int height = size.rows();
            int leftWidth = Math.max(20, width / 2);
            int bodyTop = 2;
            int bodyBottom = height - 1;
            int bodyHeight = bodyBottom - bodyTop;

            if (selected < scrollOffset) {
                scrollOffset = selected;
            } else if (selected >= scrollOffset + bodyHeight) {
                scrollOffset = selected - bodyHeight + 1;
            }

            StringBuilder buf = new StringBuilder(width * height);
            buf.append(Ansi.CLEAR_SCREEN);
            drawHeader(buf, width);
            drawTree(buf, items, bodyTop, bodyHeight, leftWidth);
            drawDivider(buf, leftWidth, bodyTop, bodyBottom);
            if (!items.isEmpty()) {
                drawDetails(buf, items.get(selected).node(),
                        leftWidth + 2, bodyTop, width - leftWidth - 2, bodyHeight);
            }
            drawFooter(buf, width, height);
            buf.append(Ansi.moveTo(height, 1));
            term.write(buf.toString());
            term.flush();
        }

        private void drawHeader(StringBuilder buf, int width) {
            String header = " vortex-inspect — v" + tree.version()
                    + "  " + formatBytes(tree.fileSize())
                    + "  rows=" + tree.totalRowCount()
                    + "  segs=" + tree.segmentCount()
                    + " (" + formatBytes(tree.totalSegmentBytes()) + ")";
            buf.append(Ansi.moveTo(1, 1));
            buf.append(Ansi.bg(46)).append(Ansi.fg(30));
            buf.append(pad(header, width));
            buf.append(Ansi.RESET);
        }

        private void drawFooter(StringBuilder buf, int width, int height) {
            buf.append(Ansi.moveTo(height, 1));
            buf.append(Ansi.bg(47)).append(Ansi.fg(30));
            buf.append(pad(" ↑↓ nav   →/Enter expand   ← collapse   q quit ", width));
            buf.append(Ansi.RESET);
        }

        private void drawTree(StringBuilder buf, List<Item> items, int top, int rows, int leftWidth) {
            for (int row = 0; row < rows; row++) {
                int idx = scrollOffset + row;
                buf.append(Ansi.moveTo(top + row + 1, 1));
                if (idx >= items.size()) {
                    buf.append(pad("", leftWidth - 1));
                    continue;
                }
                Item item = items.get(idx);
                boolean isSelected = idx == selected;
                if (isSelected) {
                    buf.append(Ansi.bg(43)).append(Ansi.fg(30));
                }
                buf.append(pad(renderItem(item), leftWidth - 1));
                if (isSelected) {
                    buf.append(Ansi.RESET);
                }
            }
        }

        private String renderItem(Item item) {
            InspectorTree.Node node = item.node();
            String marker;
            if (node.children().isEmpty()) {
                marker = "  ";
            } else if (expanded.contains(node)) {
                marker = "v ";
            } else {
                marker = "> ";
            }
            String label = item.depth() == 0 && node.layout().isStruct()
                    ? "struct"
                    : node.fieldName().map(n -> n + ": ").orElse("") + node.layout().encodingId();
            return " ".repeat(item.depth() * 2) + marker + label
                    + "  (" + node.layout().rowCount() + " rows)";
        }

        private void drawDivider(StringBuilder buf, int col, int top, int bottom) {
            for (int y = top; y < bottom; y++) {
                buf.append(Ansi.moveTo(y + 1, col + 1)).append('|');
            }
        }

        private void drawDetails(StringBuilder buf, InspectorTree.Node node,
                int col, int top, int width, int rows) {
            List<String> lines = detailLines(node);
            for (int i = 0; i < lines.size() && i < rows; i++) {
                buf.append(Ansi.moveTo(top + i + 1, col + 1));
                buf.append(truncate(lines.get(i), width));
            }
        }

        private List<String> detailLines(InspectorTree.Node node) {
            List<String> lines = new ArrayList<>();
            Layout layout = node.layout();
            lines.add("Encoding:  " + layout.encodingId());
            node.fieldName().ifPresent(name -> lines.add("Field:     " + name));
            lines.add("Rows:      " + layout.rowCount());
            lines.add("Children:  " + layout.children().size());
            if (!layout.segments().isEmpty()) {
                long subtotal = 0;
                for (int idx : layout.segments()) {
                    subtotal += tree.segmentSpecs().get(idx).length();
                }
                lines.add("Segments:  " + layout.segments().size()
                        + " (" + formatBytes(subtotal) + ")");
                for (int idx : layout.segments()) {
                    SegmentSpec spec = tree.segmentSpecs().get(idx);
                    lines.add("  [" + idx + "] off=" + spec.offset()
                            + "  len=" + formatBytes(spec.length())
                            + "  comp=" + spec.compression().name());
                }
            } else {
                lines.add("Segments:  0");
            }
            if (!node.usedEncodings().isEmpty()) {
                lines.add("");
                lines.add("Used encodings:");
                for (String enc : node.usedEncodings()) {
                    lines.add("  - " + enc);
                }
            }
            if (node.stats().min() != null || node.stats().max() != null) {
                lines.add("");
                lines.add("Stats:");
                if (node.stats().min() != null) {
                    lines.add("  min: " + node.stats().min());
                }
                if (node.stats().max() != null) {
                    lines.add("  max: " + node.stats().max());
                }
            }
            return lines;
        }

        private record Item(InspectorTree.Node node, int depth) {
        }

        private static String pad(String s, int width) {
            if (s.length() >= width) {
                return s.substring(0, width);
            }
            return s + " ".repeat(width - s.length());
        }

        private static String truncate(String s, int width) {
            return s.length() > width ? s.substring(0, width) : s;
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
}
