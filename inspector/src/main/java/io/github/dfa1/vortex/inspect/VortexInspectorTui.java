package io.github.dfa1.vortex.inspect;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.SegmentSpec;
import io.github.dfa1.vortex.io.VortexHandle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Lanterna-based interactive viewer for a Vortex file's inspector tree.
///
/// Renders a two-pane terminal UI: layout tree on the left, node details on the right.
/// Quit with `q` or `Esc`.
public final class VortexInspectorTui {

    private VortexInspectorTui() {
    }

    /// Opens a Lanterna terminal, builds an inspector tree, and runs the interactive viewer until quit.
    ///
    /// @param handle open Vortex file handle
    /// @throws IOException if the terminal cannot be initialized
    public static void show(VortexHandle handle) throws IOException {
        InspectorTree tree = InspectorTree.build(handle);
        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        Screen screen = new TerminalScreen(terminal);
        screen.startScreen();
        try {
            new Loop(screen, tree).run();
        } finally {
            screen.stopScreen();
        }
    }

    private static final class Loop {
        private final Screen screen;
        private final InspectorTree tree;
        private final Set<InspectorTree.Node> expanded = new HashSet<>();
        private int selected;
        private int scrollOffset;

        Loop(Screen screen, InspectorTree tree) {
            this.screen = screen;
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
                KeyStroke key = screen.readInput();
                if (key == null) {
                    continue;
                }
                if (isQuit(key)) {
                    return;
                }
                handleKey(key, items);
            }
        }

        private void handleKey(KeyStroke key, List<Item> items) {
            switch (key.getKeyType()) {
                case ArrowDown -> selected = Math.min(selected + 1, items.size() - 1);
                case ArrowUp -> selected = Math.max(selected - 1, 0);
                case ArrowRight, Enter -> {
                    if (selected < items.size()) {
                        InspectorTree.Node n = items.get(selected).node();
                        if (!n.children().isEmpty()) {
                            expanded.add(n);
                        }
                    }
                }
                case ArrowLeft -> {
                    if (selected < items.size()) {
                        expanded.remove(items.get(selected).node());
                    }
                }
                case PageDown -> selected = Math.min(selected + 10, items.size() - 1);
                case PageUp -> selected = Math.max(selected - 10, 0);
                case Home -> selected = 0;
                case End -> selected = items.size() - 1;
                default -> {
                }
            }
        }

        private static boolean isQuit(KeyStroke key) {
            if (key.getKeyType() == KeyType.Escape || key.getKeyType() == KeyType.EOF) {
                return true;
            }
            return key.getKeyType() == KeyType.Character
                    && key.getCharacter() != null
                    && (key.getCharacter() == 'q' || key.getCharacter() == 'Q');
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
            screen.doResizeIfNecessary();
            TerminalSize size = screen.getTerminalSize();
            screen.clear();
            TextGraphics tg = screen.newTextGraphics();
            int width = size.getColumns();
            int height = size.getRows();
            int leftWidth = Math.max(20, width / 2);

            drawHeader(tg, width);
            drawFooter(tg, width, height);

            int bodyTop = 2;
            int bodyBottom = height - 2;
            int bodyHeight = bodyBottom - bodyTop;

            if (selected < scrollOffset) {
                scrollOffset = selected;
            } else if (selected >= scrollOffset + bodyHeight) {
                scrollOffset = selected - bodyHeight + 1;
            }

            drawTree(tg, items, bodyTop, bodyHeight, leftWidth);
            drawDivider(tg, leftWidth, bodyTop, bodyBottom);
            if (!items.isEmpty()) {
                drawDetails(tg, items.get(selected).node(), leftWidth + 2, bodyTop, width - leftWidth - 2, bodyHeight);
            }

            screen.refresh();
        }

        private void drawHeader(TextGraphics tg, int width) {
            tg.setForegroundColor(TextColor.ANSI.BLACK);
            tg.setBackgroundColor(TextColor.ANSI.CYAN);
            String header = " vortex-inspect — v" + tree.version()
                    + "  " + formatBytes(tree.fileSize())
                    + "  rows=" + tree.totalRowCount()
                    + "  segs=" + tree.segmentCount()
                    + " (" + formatBytes(tree.totalSegmentBytes()) + ")";
            String padded = pad(header, width);
            tg.putString(0, 0, padded);
            tg.setBackgroundColor(TextColor.ANSI.DEFAULT);
            tg.setForegroundColor(TextColor.ANSI.DEFAULT);
        }

        private void drawFooter(TextGraphics tg, int width, int height) {
            tg.setForegroundColor(TextColor.ANSI.BLACK);
            tg.setBackgroundColor(TextColor.ANSI.WHITE);
            String hint = " ↑↓ nav   →/Enter expand   ← collapse   q quit ";
            tg.putString(0, height - 1, pad(hint, width));
            tg.setBackgroundColor(TextColor.ANSI.DEFAULT);
            tg.setForegroundColor(TextColor.ANSI.DEFAULT);
        }

        private void drawTree(TextGraphics tg, List<Item> items, int top, int rows, int leftWidth) {
            for (int row = 0; row < rows; row++) {
                int idx = scrollOffset + row;
                if (idx >= items.size()) {
                    break;
                }
                Item item = items.get(idx);
                boolean isSelected = idx == selected;
                if (isSelected) {
                    tg.setForegroundColor(TextColor.ANSI.BLACK);
                    tg.setBackgroundColor(TextColor.ANSI.YELLOW);
                } else {
                    tg.setForegroundColor(TextColor.ANSI.DEFAULT);
                    tg.setBackgroundColor(TextColor.ANSI.DEFAULT);
                }
                tg.putString(0, top + row, pad(renderItem(item), leftWidth - 1));
            }
            tg.setBackgroundColor(TextColor.ANSI.DEFAULT);
            tg.setForegroundColor(TextColor.ANSI.DEFAULT);
        }

        private String renderItem(Item item) {
            InspectorTree.Node node = item.node();
            String marker = node.children().isEmpty()
                    ? "  "
                    : (expanded.contains(node) ? "▼ " : "▶ ");
            String label = item.depth() == 0 && node.layout().isStruct()
                    ? "struct"
                    : node.fieldName().map(n -> n + ": ").orElse("") + node.layout().encodingId();
            return " ".repeat(item.depth() * 2) + marker + label
                    + "  (" + node.layout().rowCount() + " rows)";
        }

        private void drawDivider(TextGraphics tg, int col, int top, int bottom) {
            for (int y = top; y < bottom; y++) {
                tg.setCharacter(col, y, new TextCharacter('│'));
            }
        }

        private void drawDetails(TextGraphics tg, InspectorTree.Node node, int col, int top, int width, int rows) {
            List<String> lines = new ArrayList<>();
            Layout layout = node.layout();
            lines.add("Encoding:  " + layout.encodingId());
            node.fieldName().ifPresent(name -> lines.add("Field:     " + name));
            lines.add("Rows:      " + layout.rowCount());
            lines.add("Children:  " + layout.children().size());
            if (!layout.segments().isEmpty()) {
                long subtotal = 0;
                for (int idx : layout.segments()) {
                    SegmentSpec spec = tree.segmentSpecs().get(idx);
                    subtotal += spec.length();
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
                    lines.add("  • " + enc);
                }
            }
            for (int i = 0; i < lines.size() && i < rows; i++) {
                tg.putString(col, top + i, truncate(lines.get(i), width));
            }
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
