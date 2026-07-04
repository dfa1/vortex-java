package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.cli.tui.term.Ansi;
import io.github.dfa1.vortex.cli.tui.term.Key;
import io.github.dfa1.vortex.cli.tui.term.Terminal;
import io.github.dfa1.vortex.inspect.ByteSize;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.layout.ZonedStatsSchema;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
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
/// the right. Quit with `q` or `Esc`.
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
    /// The `progress` parameter is retained for source compatibility
    /// but is no longer invoked - shallow build does no peeks.
    ///
    /// @param handle   open Vortex file handle
    /// @param progress unused; kept for API stability
    /// @throws IOException if the terminal cannot be initialized
    public static void show(VortexHandle handle, InspectorTree.Progress progress) throws IOException {
        show(handle, null, progress);
    }

    /// Variant that dispatches every `handle` I/O call onto the supplied
    /// [IoWorker]. Required when the handle was opened on a different
    /// thread (Vortex readers use a confined [java.lang.foreign.Arena],
    /// so cross-thread access throws `WrongThreadException`).
    ///
    /// Passing `null` for `worker` falls back to synchronous I/O
    /// on the render thread — fine for tests but causes the sluggishness this
    /// machinery was built to avoid.
    ///
    /// @param handle   open Vortex file handle
    /// @param worker   I/O dispatcher that owns the handle's thread; may be `null`
    /// @param progress unused; kept for API stability
    /// @throws IOException if the terminal cannot be initialized
    public static void show(VortexHandle handle, IoWorker worker, InspectorTree.Progress progress)
            throws IOException {
        InspectorTree tree = InspectorTree.buildShallow(handle);
        try (Terminal term = Terminal.open()) {
            run(term, tree, handle, worker);
        }
    }

    /// Runs the inspector viewer against a caller-supplied terminal. Package-private
    /// seam used by tests to drive the loop with a scripted fake terminal,
    /// bypassing the OS raw-mode setup in
    /// [#show(VortexHandle, IoWorker, InspectorTree.Progress)].
    ///
    /// @param term   terminal to render to and read keys from
    /// @param tree   pre-built inspector tree for `handle`
    /// @param handle open Vortex file handle
    /// @param worker I/O dispatcher; may be `null` for synchronous render-thread I/O
    /// @throws IOException if the terminal write fails
    static void run(Terminal term, InspectorTree tree, VortexHandle handle, IoWorker worker)
            throws IOException {
        new Loop(term, tree, handle, worker).run();
    }

    private static final class Loop {
        /// Bytes shown per Flat segment when falling back to the raw hex view.
        private static final int HEX_PREVIEW_BYTES = 256;

        /// Decoded values shown per column in the data view.
        private static final int DATA_PREVIEW_ROWS = 32;

        /// Render cadence while idle — drives spinner animation and reaping of
        /// background fetches so updates land even when the user isn't typing.
        private static final Duration POLL_INTERVAL = Duration.ofMillis(80);

        /// ASCII spinner frames; cycled by render tick.
        private static final char[] SPINNER = {'|', '/', '-', '\\'};

        private final Terminal term;
        private final InspectorTree tree;
        private final VortexHandle handle;
        private final IoWorker worker;
        // Identity-keyed containers throughout: InspectorTree.Node wraps a
        // Layout record whose ByteBuffer metadata field crashes with
        // WrongThreadException when its hashCode reads arena-confined bytes
        // from any thread other than the handle's owner. Identity hashing
        // sidesteps that entirely and matches the natural semantics — Nodes
        // are constructed exactly once per shallow build and uniquely
        // identify a position in the tree.
        private final Set<InspectorTree.Node> expanded =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<InspectorTree.Node, InspectorTree.Peek> peekCache =
                Collections.synchronizedMap(new IdentityHashMap<>());
        private final Set<InspectorTree.Node> peekInFlight =
                Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        private final Map<InspectorTree.Node, byte[]> hexCache =
                Collections.synchronizedMap(new IdentityHashMap<>());
        private final Set<InspectorTree.Node> hexInFlight =
                Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        private final ConcurrentMap<String, DataState> dataCache = new ConcurrentHashMap<>();
        private final Map<InspectorTree.Node, DataState> dictCache =
                Collections.synchronizedMap(new IdentityHashMap<>());
        private final Map<InspectorTree.Node, DataState> statsCache =
                Collections.synchronizedMap(new IdentityHashMap<>());
        private final Map<InspectorTree.Node, String> columnOf = new IdentityHashMap<>();
        private final Set<InspectorTree.Node> statsChildren =
                Collections.newSetFromMap(new IdentityHashMap<>());
        /// Maps each stats-payload child Node to the parent Zoned/Chunked Node so we
        /// can recover the column dtype + layout metadata needed for stats decode.
        private final Map<InspectorTree.Node, InspectorTree.Node> statsParentOf =
                new IdentityHashMap<>();
        private volatile String lastError;
        private long tick;
        private int selected;
        private int scrollOffset;

        Loop(Terminal term, InspectorTree tree, VortexHandle handle, IoWorker worker) {
            this.term = term;
            this.tree = tree;
            this.handle = handle;
            this.worker = worker;
            this.expanded.add(tree.root());
            indexColumns(tree.root());
            indexStatsChildrenOnWorker(tree.root());
            prefetchTopColumns();
        }

        private void indexStatsChildrenOnWorker(InspectorTree.Node root) {
            if (worker == null) {
                indexStatsChildren(root);
                return;
            }
            try {
                worker.runAndAwait(() -> indexStatsChildren(root));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        private void indexStatsChildren(InspectorTree.Node node) {
            Layout layout = node.layout();
            if (layout.isZoned() && node.children().size() >= 2) {
                // Zoned: child[0] = data, child[1] = per-chunk stats payload
                InspectorTree.Node statsChild = node.children().get(1);
                statsChildren.add(statsChild);
                statsParentOf.put(statsChild, node);
            } else if (layout.isChunked() && hasLeadingStats(layout) && !node.children().isEmpty()) {
                // Chunked with metadata[0] == 1: child[0] is the stats payload
                InspectorTree.Node statsChild = node.children().get(0);
                statsChildren.add(statsChild);
                statsParentOf.put(statsChild, node);
            }
            for (InspectorTree.Node child : node.children()) {
                indexStatsChildren(child);
            }
        }

        private static boolean hasLeadingStats(Layout layout) {
            MemorySegment meta = layout.metadata();
            return meta != null && meta.byteSize() > 0
                    && meta.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0) == 1;
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
                Optional<Key> maybeKey = term.readKey(POLL_INTERVAL);
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
                case Key.ArrowDown _ -> selected = Math.min(selected + 1, items.size() - 1);
                case Key.ArrowUp _ -> selected = Math.max(selected - 1, 0);
                case Key.ArrowRight _ -> expandSelected(items);
                case Key.Enter _ -> toggleSelected(items);
                case Key.ArrowLeft _ -> {
                    if (selected < items.size()) {
                        expanded.remove(items.get(selected).node());
                    }
                }
                case Key.PageDown _ -> selected = Math.min(selected + 10, items.size() - 1);
                case Key.PageUp _ -> selected = Math.max(selected - 10, 0);
                case Key.Home _ -> selected = 0;
                case Key.End _ -> selected = items.size() - 1;
                default -> { /* ignore unhandled keys */ }
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
            Terminal.Size size = term.size();
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
            buf.append(InspectorRender.pad(text, width));
            buf.append(Ansi.RESET);
        }

        private void drawHeader(StringBuilder buf, int width) {
            String header = " vortex-inspect — v" + tree.version()
                    + "  " + ByteSize.format(tree.fileSize())
                    + "  rows=" + tree.totalRowCount()
                    + "  segs=" + tree.segmentCount()
                    + " (" + ByteSize.format(tree.totalSegmentBytes()) + ")";
            buf.append(Ansi.moveTo(1, 1));
            buf.append(Ansi.bg(46)).append(Ansi.fg(30));
            buf.append(InspectorRender.pad(header, width));
            buf.append(Ansi.RESET);
        }

        private void drawFooter(StringBuilder buf, int width, int height) {
            buf.append(Ansi.moveTo(height, 1));
            buf.append(Ansi.bg(47)).append(Ansi.fg(30));
            buf.append(InspectorRender.pad(" ↑↓ nav   →/Enter expand   ← collapse   q quit ", width));
            buf.append(Ansi.RESET);
        }

        private void drawTree(StringBuilder buf, List<Item> items, int top, int rows, int leftWidth) {
            for (int row = 0; row < rows; row++) {
                int idx = scrollOffset + row;
                buf.append(Ansi.moveTo(top + row + 1, 1));
                if (idx >= items.size()) {
                    buf.append(InspectorRender.pad("", leftWidth - 1));
                    continue;
                }
                Item item = items.get(idx);
                boolean isSelected = idx == selected;
                if (isSelected) {
                    buf.append(Ansi.bg(43)).append(Ansi.fg(30));
                }
                buf.append(InspectorRender.pad(renderItem(item), leftWidth - 1));
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
                    : node.fieldName().map(n -> n + ": ").orElse("") + node.layout().layoutId();
            String tag = statsChildren.contains(node) ? ", stats" : "";
            return " ".repeat(item.depth() * 2) + marker + label
                    + "  (" + node.layout().rowCount() + " rows" + tag + ")";
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
                buf.append(InspectorRender.truncate(lines.get(i), width));
            }
        }

        private List<String> detailLines(InspectorTree.Node node) {
            List<String> lines = new ArrayList<>();
            Layout layout = node.layout();
            InspectorTree.Peek p = peek(node);
            lines.add("Encoding:  " + (p.encoding() != null ? p.encoding() : layout.layoutId().id()));
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
                        + " (" + ByteSize.format(subtotal) + ")");
                long rows = layout.rowCount();
                for (int idx : layout.segments()) {
                    SegmentSpec spec = tree.segmentSpecs().get(idx);
                    String bits = rows > 0
                            ? "  bits/elem=" + String.format("%.2f", spec.length() * 8.0 / rows)
                            : "";
                    lines.add("  [" + idx + "] off=" + spec.offset()
                            + "  len=" + ByteSize.format(spec.length())
                            + "  compression=" + spec.compression().name()
                            + bits);
                }
            } else {
                lines.add("Segments:  0");
            }
            // ArrayNode.stats deliberately not shown: many encodings (ALP, FOR,
            // bitpacked) leave min/max at zero in the FlatBuffer because real
            // stats live in encoding-specific metadata or in the zone-map.
            // Displaying them as a "Stats" block was misleading. Per-chunk
            // zone-map stats are rendered below when present.
            if (layout.isDict() && layout.children().size() >= 1) {
                DataState dictState = loadDictPreview(node);
                lines.add("");
                switch (dictState) {
                    case DataState.Pending _ ->
                            lines.add("Dictionary:  " + SPINNER[(int) (tick % SPINNER.length)] + " loading...");
                    case DataState.Failed(String msg) ->
                            lines.add("Dictionary:  ! " + msg);
                    case DataState.Loaded(List<String> values) -> {
                        lines.add("Dictionary (" + values.size() + " entries):");
                        for (int i = 0; i < values.size(); i++) {
                            lines.add(String.format("  [%2d] %s", i, values.get(i)));
                        }
                    }
                }
            }
            // Per-zone stats — show when the selected node carries a zone-map (Zoned
            // or Chunked-with-leading-stats) or when the selected node is itself the
            // stats-payload child of one. Decoded asynchronously to keep nav snappy.
            InspectorTree.Node zoneAnchor = zoneStatsAnchor(node);
            if (zoneAnchor != null) {
                DataState zoneState = loadStatsPreview(zoneAnchor);
                lines.add("");
                switch (zoneState) {
                    case DataState.Pending _ ->
                            lines.add("Per-chunk stats:  "
                                    + SPINNER[(int) (tick % SPINNER.length)] + " loading...");
                    case DataState.Failed(String msg) ->
                            lines.add("Per-chunk stats:  ! " + msg);
                    case DataState.Loaded(List<String> rows) -> {
                        lines.add("Per-chunk stats (" + rows.size() + " chunks):");
                        for (int i = 0; i < rows.size(); i++) {
                            lines.add(String.format("  [%2d] %s", i, rows.get(i)));
                        }
                    }
                }
            }
            if (col != null) {
                DataState state = loadDataPreview(col);
                lines.add("");
                switch (state) {
                    case DataState.Pending _ ->
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
                            + segIdx + ", total " + ByteSize.format(spec.length()) + "):");
                    for (int off = 0; off < preview.length; off += 16) {
                        lines.add(InspectorRender.formatHexRow(preview, off));
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

        private DataState loadDictPreview(InspectorTree.Node dictNode) {
            DataState existing = dictCache.get(dictNode);
            if (existing != null) {
                return existing;
            }
            if (dictCache.putIfAbsent(dictNode, DataState.PENDING) != null) {
                return dictCache.get(dictNode);
            }
            if (worker == null) {
                runDictLoad(dictNode);
            } else {
                worker.submit(() -> runDictLoad(dictNode));
            }
            return dictCache.getOrDefault(dictNode, DataState.PENDING);
        }

        private void runDictLoad(InspectorTree.Node dictNode) {
            try {
                Layout values = dictNode.layout().children().get(0);
                DType dtype = columnDtypeFor(dictNode);
                if (dtype == null) {
                    dictCache.put(dictNode, new DataState.Loaded(List.of()));
                    return;
                }
                try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
                    int segIdx = values.segments().getFirst();
                    SegmentSpec spec = tree.segmentSpecs().get(segIdx);
                    io.github.dfa1.vortex.reader.array.Array arr =
                            handle.decodeSegment(spec, dtype, values.rowCount(), arena);
                    int n = (int) Math.min(arr.length(), DATA_PREVIEW_ROWS);
                    List<String> out = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        out.add(InspectorRender.formatValue(arr, i, dtype));
                    }
                    dictCache.put(dictNode, new DataState.Loaded(List.copyOf(out)));
                }
            } catch (RuntimeException e) {
                dictCache.put(dictNode, new DataState.Failed(messageOf(e)));
                lastError = "dict: " + messageOf(e);
            }
        }

        /// Returns the Zoned/Chunked-with-leading-stats parent whose per-zone stats
        /// table we want to render when the given node is selected, or `null`
        /// when no zone-map is associated with the node.
        ///
        /// Selecting either the parent (Zoned or Chunked-with-leading-stats) or the
        /// stats-payload child surfaces the same table — both selections land here.
        private InspectorTree.Node zoneStatsAnchor(InspectorTree.Node node) {
            if (statsChildren.contains(node)) {
                return statsParentOf.get(node);
            }
            Layout layout = node.layout();
            if (layout.isZoned() && node.children().size() >= 2) {
                return node;
            }
            if (layout.isChunked() && hasLeadingStats(layout) && !node.children().isEmpty()) {
                return node;
            }
            return null;
        }

        private DataState loadStatsPreview(InspectorTree.Node anchor) {
            DataState existing = statsCache.get(anchor);
            if (existing != null) {
                return existing;
            }
            if (statsCache.putIfAbsent(anchor, DataState.PENDING) != null) {
                return statsCache.get(anchor);
            }
            if (worker == null) {
                runStatsLoad(anchor);
            } else {
                worker.submit(() -> runStatsLoad(anchor));
            }
            return statsCache.getOrDefault(anchor, DataState.PENDING);
        }

        private void runStatsLoad(InspectorTree.Node anchor) {
            try {
                Layout anchorLayout = anchor.layout();
                int statsChildIdx = anchorLayout.isZoned() ? 1 : 0;
                if (anchor.children().size() <= statsChildIdx) {
                    statsCache.put(anchor, new DataState.Loaded(List.of()));
                    return;
                }
                InspectorTree.Node statsChild = anchor.children().get(statsChildIdx);
                Layout statsLayout = statsChild.layout();
                DType columnDtype = columnDtypeFor(anchor);
                if (columnDtype == null) {
                    statsCache.put(anchor, new DataState.Failed("no column dtype"));
                    return;
                }
                DType.Struct statsDtype = ZonedStatsSchema.statsTableDtype(columnDtype, anchorLayout.metadata());
                if (statsDtype.fieldNames().isEmpty()) {
                    statsCache.put(anchor, new DataState.Failed("no stats present in metadata"));
                    return;
                }
                try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
                    List<String> rows = decodeStatsLayout(statsLayout, statsDtype, arena);
                    statsCache.put(anchor, new DataState.Loaded(List.copyOf(rows)));
                }
            } catch (RuntimeException e) {
                statsCache.put(anchor, new DataState.Failed(messageOf(e)));
                lastError = "stats: " + messageOf(e);
            }
        }

        /// Decodes a stats-payload subtree into one display row per zone.
        ///
        /// Only Flat and Chunked-of-Flat shapes are supported — anything else
        /// (Struct, Zoned, Dict) reports an explanatory error rather than
        /// throwing, since stats payloads in the wild are overwhelmingly Flat.
        private List<String> decodeStatsLayout(
                Layout statsLayout, DType.Struct statsDtype, java.lang.foreign.Arena arena) {
            if (statsLayout.isFlat()) {
                return decodeStatsFlat(statsLayout, statsDtype, arena);
            }
            if (statsLayout.isChunked()) {
                // Skip a leading stats-of-stats child if present; otherwise traverse all children.
                int start = hasLeadingStats(statsLayout) ? 1 : 0;
                List<String> all = new ArrayList<>();
                for (int i = start; i < statsLayout.children().size(); i++) {
                    Layout child = statsLayout.children().get(i);
                    if (!child.isFlat()) {
                        throw new IllegalStateException(
                                "non-flat stats chunk: " + child.layoutId());
                    }
                    all.addAll(decodeStatsFlat(child, statsDtype, arena));
                }
                return all;
            }
            throw new IllegalStateException(
                    "unsupported stats layout: " + statsLayout.layoutId());
        }

        private List<String> decodeStatsFlat(
                Layout flat, DType.Struct statsDtype, java.lang.foreign.Arena arena) {
            if (flat.segments().isEmpty()) {
                return List.of();
            }
            int segIdx = flat.segments().getFirst();
            SegmentSpec spec = tree.segmentSpecs().get(segIdx);
            Array arr = handle.decodeSegment(spec, statsDtype, flat.rowCount(), arena);
            return InspectorRender.formatStatsArray(arr, statsDtype);
        }

        private DType columnDtypeFor(InspectorTree.Node node) {
            String col = columnOf.get(node);
            if (col == null) {
                return tree.dtype();
            }
            return columnDtypeByName(col);
        }

        private DType columnDtypeByName(String columnName) {
            DType root = tree.dtype();
            if (root instanceof DType.Struct s) {
                int idx = s.fieldNames().indexOf(columnName);
                if (idx >= 0) {
                    return s.fieldTypes().get(idx);
                }
            }
            return root;
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
                DType declared = columnDtypeByName(columnName);
                ScanOptions opts = ScanOptions.columns(columnName).withLimit(DATA_PREVIEW_ROWS);
                try (ScanIterator it = handle.scan(opts)) {
                    if (!it.hasNext()) {
                        dataCache.put(columnName, new DataState.Loaded(List.of()));
                        return;
                    }
                    try (Chunk chunk = it.next()) {
                        Chunk.Column column = chunk.columns().get(ColumnName.of(columnName));
                        if (column == null) {
                            dataCache.put(columnName, new DataState.Loaded(List.of()));
                            return;
                        }
                        Array array = column.array();
                        int n = (int) Math.min(array.length(), DATA_PREVIEW_ROWS);
                        List<String> out = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            out.add(InspectorRender.formatValue(array, i, declared));
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
            int wanted = (int) Math.min(HEX_PREVIEW_BYTES, spec.length());
            if (wanted <= 0) {
                return new byte[0];
            }
            try {
                MemorySegment seg = handle.rawSegment(spec);
                byte[] buf = new byte[wanted];
                MemorySegment.copy(seg, 0, MemorySegment.ofArray(buf), 0, wanted);
                return buf;
            } catch (RuntimeException e) {
                lastError = "hex: " + messageOf(e);
                return new byte[0];
            }
        }

        private record Item(InspectorTree.Node node, int depth) {
        }
    }
}
