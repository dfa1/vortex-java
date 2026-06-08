package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.SegmentSpec;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.inspect.term.Ansi;
import io.github.dfa1.vortex.inspect.term.Key;
import io.github.dfa1.vortex.inspect.term.RawTerminal;
import io.github.dfa1.vortex.io.VortexHandle;
import io.github.dfa1.vortex.scan.Chunk;
import io.github.dfa1.vortex.scan.ScanIterator;
import io.github.dfa1.vortex.scan.ScanOptions;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
    /// and runs the interactive viewer until quit. The TUI now uses the
    /// shallow builder so the screen is interactive immediately; encoding,
    /// stats and data previews are fetched lazily as the user navigates.
    /// The {@code progress} parameter is retained for source compatibility
    /// but is no longer invoked - shallow build does no peeks.
    ///
    /// @param handle   open Vortex file handle
    /// @param progress unused; kept for API stability
    /// @throws IOException if the terminal cannot be initialized
    public static void show(VortexHandle handle, InspectorTree.Progress progress) throws IOException {
        show(handle, null, progress);
    }

    /// Variant that dispatches every {@code handle} I/O call onto the supplied
    /// {@link IoWorker}. Required when the handle was opened on a different
    /// thread (Vortex readers use a confined {@link java.lang.foreign.Arena},
    /// so cross-thread access throws {@code WrongThreadException}).
    ///
    /// Passing {@code null} for {@code worker} falls back to synchronous I/O
    /// on the render thread — fine for tests but causes the sluggishness this
    /// machinery was built to avoid.
    ///
    /// @param handle   open Vortex file handle
    /// @param worker   I/O dispatcher that owns the handle's thread; may be {@code null}
    /// @param progress unused; kept for API stability
    /// @throws IOException if the terminal cannot be initialized
    public static void show(VortexHandle handle, IoWorker worker, InspectorTree.Progress progress)
            throws IOException {
        InspectorTree tree = InspectorTree.buildShallow(handle);
        try (RawTerminal term = RawTerminal.open()) {
            new Loop(term, tree, handle, worker).run();
        }
    }

    private static final class Loop {
        /// Bytes shown per Flat segment when falling back to the raw hex view.
        private static final int HEX_PREVIEW_BYTES = 256;

        /// Decoded values shown per column in the data view.
        private static final int DATA_PREVIEW_ROWS = 32;

        /// Render cadence while idle — drives spinner animation and reaping of
        /// background fetches so updates land even when the user isn't typing.
        private static final long POLL_INTERVAL_MS = 80;

        /// ASCII spinner frames; cycled by render tick.
        private static final char[] SPINNER = {'|', '/', '-', '\\'};

        private final RawTerminal term;
        private final InspectorTree tree;
        private final VortexHandle handle;
        private final IoWorker worker;
        private final Set<InspectorTree.Node> expanded = new HashSet<>();
        private final ConcurrentMap<InspectorTree.Node, InspectorTree.Peek> peekCache = new ConcurrentHashMap<>();
        private final Set<InspectorTree.Node> peekInFlight = ConcurrentHashMap.newKeySet();
        private final ConcurrentMap<InspectorTree.Node, byte[]> hexCache = new ConcurrentHashMap<>();
        private final Set<InspectorTree.Node> hexInFlight = ConcurrentHashMap.newKeySet();
        private final ConcurrentMap<String, DataState> dataCache = new ConcurrentHashMap<>();
        private final Map<InspectorTree.Node, String> columnOf = new HashMap<>();
        private volatile String lastError;
        private long tick;
        private int selected;
        private int scrollOffset;

        Loop(RawTerminal term, InspectorTree tree, VortexHandle handle, IoWorker worker) {
            this.term = term;
            this.tree = tree;
            this.handle = handle;
            this.worker = worker;
            this.expanded.add(tree.root());
            indexColumns(tree.root());
            prefetchTopColumns();
        }

        private void prefetchTopColumns() {
            if (!tree.root().layout().isStruct()) {
                return;
            }
            for (InspectorTree.Node col : tree.root().children()) {
                col.fieldName().ifPresent(this::startDataLoad);
            }
        }

        private void indexColumns(InspectorTree.Node root) {
            if (!root.layout().isStruct()) {
                return;
            }
            for (InspectorTree.Node colNode : root.children()) {
                colNode.fieldName().ifPresent(name -> tagSubtree(colNode, name));
            }
        }

        private void tagSubtree(InspectorTree.Node node, String columnName) {
            columnOf.put(node, columnName);
            for (InspectorTree.Node child : node.children()) {
                tagSubtree(child, columnName);
            }
        }

        private InspectorTree.Peek peek(InspectorTree.Node node) {
            InspectorTree.Peek cached = peekCache.get(node);
            if (cached != null) {
                return cached;
            }
            if (worker == null) {
                InspectorTree.Peek p = safePeek(node);
                peekCache.put(node, p);
                return p;
            }
            if (peekInFlight.add(node)) {
                worker.submit(() -> {
                    try {
                        peekCache.put(node, safePeek(node));
                    } finally {
                        peekInFlight.remove(node);
                    }
                });
            }
            return InspectorTree.Peek.EMPTY;
        }

        private InspectorTree.Peek safePeek(InspectorTree.Node node) {
            try {
                return InspectorTree.peek(node, handle);
            } catch (RuntimeException e) {
                lastError = "peek: " + messageOf(e);
                return InspectorTree.Peek.EMPTY;
            }
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
                Optional<Key> maybeKey = term.readKey(POLL_INTERVAL_MS);
                if (maybeKey.isEmpty()) {
                    tick++;
                    continue;
                }
                Key key = maybeKey.get();
                if (isQuit(key)) {
                    return;
                }
                handleKey(key, items);
                tick++;
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
            int bodyBottom = height - 2;
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
            drawStatus(buf, width, height - 1);
            drawFooter(buf, width, height);
            buf.append(Ansi.moveTo(height, 1));
            term.write(buf.toString());
            term.flush();
        }

        private void drawStatus(StringBuilder buf, int width, int row) {
            int loads = worker == null ? 0 : worker.pending();
            String err = lastError;
            String text;
            int bg;
            if (err != null) {
                text = " ! " + err;
                bg = 41; // red
            } else if (loads > 0) {
                text = " " + SPINNER[(int) (tick % SPINNER.length)]
                        + " I/O " + loads + " pending";
                bg = 44; // blue
            } else {
                text = " ready";
                bg = 42; // green
            }
            buf.append(Ansi.moveTo(row, 1));
            buf.append(Ansi.bg(bg)).append(Ansi.fg(30));
            buf.append(pad(text, width));
            buf.append(Ansi.RESET);
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
            InspectorTree.Peek p = peek(node);
            lines.add("Encoding:  " + (p.encoding() != null ? p.encoding() : layout.encodingId()));
            node.fieldName().ifPresent(name -> lines.add("Field:     " + name));
            String col = columnOf.get(node);
            if (col != null && !node.fieldName().isPresent()) {
                lines.add("Column:    " + col);
            }
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
            if (p.stats().min() != null || p.stats().max() != null) {
                lines.add("");
                lines.add("Stats:");
                if (p.stats().min() != null) {
                    lines.add("  min: " + p.stats().min());
                }
                if (p.stats().max() != null) {
                    lines.add("  max: " + p.stats().max());
                }
            }
            if (col != null) {
                DataState state = loadDataPreview(col);
                lines.add("");
                switch (state) {
                    case DataState.Pending ignored ->
                            lines.add("Data (column '" + col + "'):  "
                                    + SPINNER[(int) (tick % SPINNER.length)] + " loading...");
                    case DataState.Failed(String msg) ->
                            lines.add("Data (column '" + col + "'):  ! " + msg);
                    case DataState.Loaded(List<String> values) -> {
                        lines.add("Data (column '" + col + "', first " + values.size() + " rows):");
                        for (int i = 0; i < values.size(); i++) {
                            lines.add(String.format("  [%2d] %s", i, values.get(i)));
                        }
                    }
                }
            } else if (layout.isFlat() && !layout.segments().isEmpty()) {
                byte[] preview = loadHexPreview(node);
                if (preview.length > 0) {
                    lines.add("");
                    int segIdx = layout.segments().getFirst();
                    SegmentSpec spec = tree.segmentSpecs().get(segIdx);
                    lines.add("Hex (first " + preview.length + " B of segment "
                            + segIdx + ", total " + formatBytes(spec.length()) + "):");
                    for (int off = 0; off < preview.length; off += 16) {
                        lines.add(formatHexRow(preview, off));
                    }
                }
            }
            return lines;
        }

        private DataState loadDataPreview(String columnName) {
            DataState existing = dataCache.get(columnName);
            if (existing != null) {
                return existing;
            }
            startDataLoad(columnName);
            return dataCache.getOrDefault(columnName, DataState.PENDING);
        }

        private void startDataLoad(String columnName) {
            if (dataCache.putIfAbsent(columnName, DataState.PENDING) != null) {
                return;
            }
            if (worker == null) {
                runDataLoad(columnName);
                return;
            }
            worker.submit(() -> runDataLoad(columnName));
        }

        private void runDataLoad(String columnName) {
            try {
                ScanOptions opts = ScanOptions.columns(columnName).withLimit(DATA_PREVIEW_ROWS);
                try (ScanIterator it = handle.scan(opts)) {
                    if (!it.hasNext()) {
                        dataCache.put(columnName, new DataState.Loaded(List.of()));
                        return;
                    }
                    try (Chunk chunk = it.next()) {
                        Array array = chunk.columns().get(columnName);
                        if (array == null) {
                            dataCache.put(columnName, new DataState.Loaded(List.of()));
                            return;
                        }
                        int n = (int) Math.min(array.length(), DATA_PREVIEW_ROWS);
                        List<String> out = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            out.add(formatValue(array, i));
                        }
                        dataCache.put(columnName, new DataState.Loaded(List.copyOf(out)));
                    }
                }
            } catch (RuntimeException e) {
                dataCache.put(columnName, new DataState.Failed(messageOf(e)));
                lastError = columnName + ": " + messageOf(e);
            }
        }

        private static String messageOf(Throwable t) {
            String m = t.getMessage();
            return m != null ? m : t.getClass().getSimpleName();
        }

        /// Per-column data fetch state — pending while a virtual thread is
        /// fetching, loaded with values once decoded, failed with a message
        /// on error. Sealed so callers can pattern-match exhaustively.
        sealed interface DataState {
            /// Singleton state for a fetch in flight.
            DataState PENDING = new Pending();

            /// In-flight fetch.
            record Pending() implements DataState {
            }

            /// Completed fetch with decoded values.
            ///
            /// @param values formatted first rows of the column
            record Loaded(List<String> values) implements DataState {
            }

            /// Failed fetch carrying a short error description.
            ///
            /// @param message short error string
            record Failed(String message) implements DataState {
            }
        }

        private static String formatValue(Array array, int i) {
            return switch (array) {
                case LongArray a -> Long.toString(a.getLong(i));
                case IntArray a -> Integer.toString(a.getInt(i));
                case ShortArray a -> Short.toString(a.getShort(i));
                case ByteArray a -> Byte.toString(a.getByte(i));
                case DoubleArray a -> Double.toString(a.getDouble(i));
                case FloatArray a -> Float.toString(a.getFloat(i));
                case BoolArray a -> Boolean.toString(a.getBoolean(i));
                case VarBinArray a -> a.dtype() instanceof io.github.dfa1.vortex.core.DType.Utf8
                        ? "\"" + a.getString(i) + "\""
                        : bytesToShortHex(a.getBytes(i));
                default -> "<" + array.getClass().getSimpleName() + ">";
            };
        }

        private static String bytesToShortHex(byte[] bytes) {
            int n = Math.min(bytes.length, 16);
            StringBuilder sb = new StringBuilder(n * 3 + 2);
            sb.append("0x");
            for (int i = 0; i < n; i++) {
                sb.append(String.format("%02x", bytes[i] & 0xff));
            }
            if (bytes.length > n) {
                sb.append("...");
            }
            return sb.toString();
        }

        private byte[] loadHexPreview(InspectorTree.Node node) {
            byte[] cached = hexCache.get(node);
            if (cached != null) {
                return cached;
            }
            if (worker == null) {
                byte[] bytes = fetchHex(node);
                hexCache.put(node, bytes);
                return bytes;
            }
            if (hexInFlight.add(node)) {
                worker.submit(() -> {
                    try {
                        hexCache.put(node, fetchHex(node));
                    } finally {
                        hexInFlight.remove(node);
                    }
                });
            }
            return new byte[0];
        }

        private byte[] fetchHex(InspectorTree.Node node) {
            Layout layout = node.layout();
            int segIdx = layout.segments().getFirst();
            SegmentSpec spec = tree.segmentSpecs().get(segIdx);
            int wanted = (int) Math.min((long) HEX_PREVIEW_BYTES, spec.length());
            if (wanted <= 0) {
                return new byte[0];
            }
            try {
                MemorySegment seg = handle.slice(spec.offset(), wanted);
                byte[] buf = new byte[wanted];
                MemorySegment.copy(seg, 0, MemorySegment.ofArray(buf), 0, wanted);
                return buf;
            } catch (RuntimeException e) {
                lastError = "hex: " + messageOf(e);
                return new byte[0];
            }
        }

        private static String formatHexRow(byte[] data, int offset) {
            StringBuilder sb = new StringBuilder(80);
            sb.append(String.format("%08x  ", offset));
            for (int i = 0; i < 16; i++) {
                int idx = offset + i;
                if (idx < data.length) {
                    sb.append(String.format("%02x ", data[idx] & 0xff));
                } else {
                    sb.append("   ");
                }
                if (i == 7) {
                    sb.append(' ');
                }
            }
            sb.append(" |");
            for (int i = 0; i < 16; i++) {
                int idx = offset + i;
                if (idx >= data.length) {
                    sb.append(' ');
                    continue;
                }
                int b = data[idx] & 0xff;
                sb.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
            }
            sb.append('|');
            return sb.toString();
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
