package io.github.dfa1.vortex.inspect;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;

import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Footer;
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.core.fbs.FbsArray;
import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.Zone;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Structured snapshot of a Vortex file's schema, layout, and encoding usage.
///
/// Built once from a [VortexHandle] via [#build(VortexHandle)] and then consumed by renderers
/// (text or TUI). Immutable — does not retain the handle.
///
/// @param version          Vortex file format version stored in the trailer
/// @param fileSize         total file length in bytes
/// @param dtype            top-level data type (typically [DType.Struct])
/// @param registeredEncodings encoding IDs declared in the file footer
/// @param usedEncodings    encoding IDs actually referenced by Flat layout segments
/// @param segmentSpecs     all on-disk segments referenced by the footer, in index order
/// @param totalRowCount    total logical rows in the file (root layout's row count)
/// @param segmentEncodings  segment index to the encoding id at the root of the array stored there -
///                          the encoding a reader dispatches on for that segment, as opposed to
///                          [#usedEncodings()], which is every id anywhere in the tree. Empty for a
///                          tree built by [#buildShallow(VortexHandle)], which peeks nothing.
/// @param root             root layout node
public record InspectorTree(
        int version,
        long fileSize,
        DType dtype,
        List<String> registeredEncodings,
        Set<String> usedEncodings,
        List<SegmentSpec> segmentSpecs,
        long totalRowCount,
        Map<Integer, String> segmentEncodings,
        Node root) {

    /// Number of on-disk segments referenced by the footer.
    ///
    /// @return segment count
    public int segmentCount() {
        return segmentSpecs.size();
    }

    /// Sum of segment lengths in bytes.
    ///
    /// @return total segment bytes
    public long totalSegmentBytes() {
        long total = 0;
        for (SegmentSpec spec : segmentSpecs) {
            total += spec.length();
        }
        return total;
    }

    /// One layout node in the inspector tree.
    ///
    /// @param layout         underlying [Layout] from the file footer
    /// @param fieldName      column name when this node is a direct child of a top-level struct
    /// @param usedEncodings  encoding IDs referenced by this subtree
    /// @param stats          per-array statistics decoded from the segment's FlatBuffer; for a
    ///                       zoned top-level column and the chunks its zones tile, the min/max
    ///                       folded from its zone-map table
    /// @param children       child nodes
    public record Node(
            Layout layout,
            Optional<String> fieldName,
            Set<String> usedEncodings,
            ArrayStats stats,
            List<Node> children) {
    }

    /// Builds an inspector tree from an open Vortex file handle.
    ///
    /// @param handle open file handle
    /// @return immutable inspector tree
    public static InspectorTree build(VortexHandle handle) {
        return build(handle, Progress.NOOP);
    }

    /// Builds an inspector tree without peeking segments — every node starts
    /// with an empty encoding set and [ArrayStats#empty()] stats. The
    /// resulting tree contains only structure derived from the file's footer
    /// and layout, so the call is essentially free on remote handles.
    ///
    /// Use with [#peek(Node, VortexHandle)] for lazy on-demand resolution.
    ///
    /// @param handle open file handle
    /// @return immutable shallow inspector tree
    public static InspectorTree buildShallow(VortexHandle handle) {
        Footer footer = handle.footer();
        Layout layout = handle.layout();
        DType dtype = handle.dtype();
        List<String> colNames = (dtype instanceof DType.Struct s)
                ? s.fieldNames().stream().map(ColumnName::value).toList() : List.of();
        Node root = shallowNode(layout, Optional.empty());
        if (layout.isStruct()) {
            List<Node> named = new ArrayList<>(root.children().size());
            for (int i = 0; i < root.children().size(); i++) {
                Node child = root.children().get(i);
                String name = i < colNames.size() ? colNames.get(i) : "col" + i;
                named.add(new Node(child.layout(), Optional.of(name),
                        Set.of(), ArrayStats.empty(), child.children()));
            }
            root = new Node(root.layout(), Optional.empty(), Set.of(),
                    ArrayStats.empty(), List.copyOf(named));
        }
        return new InspectorTree(
                handle.version(),
                handle.fileSize(),
                dtype,
                footer.arraySpecs().stream().map(EncodingId::id).toList(),
                Set.of(),
                footer.segmentSpecs(),
                layout.rowCount(),
                Map.of(),
                root);
    }

    private static Node shallowNode(Layout layout, Optional<String> fieldName) {
        List<Node> children = new ArrayList<>(layout.children().size());
        for (Layout child : layout.children()) {
            children.add(shallowNode(child, Optional.empty()));
        }
        return new Node(layout, fieldName, Set.of(), ArrayStats.empty(), List.copyOf(children));
    }

    /// Resolves encoding id + stats for one Flat node by reading its first
    /// segment. Returns [Peek#EMPTY] for non-Flat nodes, segments under
    /// compression, or missing data.
    ///
    /// Callers should cache the result — every call triggers a fresh
    /// [io.github.dfa1.vortex.reader.VortexHandle#rawSegment], which is a network round-trip on remote handles.
    ///
    /// @param node   node to resolve
    /// @param handle open file handle
    /// @return peek result; never `null`
    public static Peek peek(Node node, VortexHandle handle) {
        Layout layout = node.layout();
        if (!layout.isFlat() || layout.segments().isEmpty()) {
            return Peek.EMPTY;
        }
        int segIdx = layout.segments().getFirst();
        SegmentSpec spec = handle.footer().segmentSpecs().get(segIdx);
        if (spec.compression().code != 0) {
            return Peek.EMPTY;
        }
        MemorySegment seg = handle.rawSegment(spec);
        return peekFlatRoot(seg, handle.footer().arraySpecs());
    }

    /// Builds an inspector tree from an open Vortex file handle, reporting
    /// progress on each Flat-segment peek (which on remote-storage handles
    /// triggers a separate HTTP range request).
    ///
    /// @param handle   open file handle
    /// @param progress progress sink receiving `(current, total)` after each segment peek
    /// @return immutable inspector tree
    public static InspectorTree build(VortexHandle handle, Progress progress) {
        Footer footer = handle.footer();
        Layout layout = handle.layout();
        DType dtype = handle.dtype();

        int total = countPeekableSegments(layout, footer);
        int[] counter = {0};

        List<String> colNames = (dtype instanceof DType.Struct s)
                ? s.fieldNames().stream().map(ColumnName::value).toList() : List.of();
        Set<String> overallUsed = new LinkedHashSet<>();
        Map<Integer, String> segmentEncodings = new LinkedHashMap<>();
        Node root = buildNode(layout, Optional.empty(), handle, footer.arraySpecs(),
                overallUsed, segmentEncodings, progress, counter, total);
        if (layout.isStruct()) {
            List<Node> namedChildren = new ArrayList<>(root.children().size());
            for (int i = 0; i < root.children().size(); i++) {
                Node child = root.children().get(i);
                String name = i < colNames.size() ? colNames.get(i) : "col" + i;
                Node named = new Node(child.layout(), Optional.of(name),
                        child.usedEncodings(), child.stats(), child.children());
                namedChildren.add(i < colNames.size() && child.layout().isZoned()
                        ? withZoneStats(named, columnZones(handle, name)) : named);
            }
            root = new Node(root.layout(), Optional.empty(), root.usedEncodings(),
                    root.stats(), List.copyOf(namedChildren));
        }

        return new InspectorTree(
                handle.version(),
                handle.fileSize(),
                dtype,
                footer.arraySpecs().stream().map(EncodingId::id).toList(),
                Set.copyOf(overallUsed),
                footer.segmentSpecs(),
                layout.rowCount(),
                Map.copyOf(segmentEncodings),
                root);
    }

    private static List<Zone> columnZones(VortexHandle handle, String column) {
        try (ScanIterator scan = handle.scan(ScanOptions.all())) {
            return scan.columnZones(ColumnName.of(column));
        }
    }

    /// Takes a column's min/max from its zone-map table instead of its segments' array-level stats:
    /// the Rust writer records bounds only there (#416), and a dictionary column's segments hold
    /// codes, whose array-level min/max describe the codes rather than the values. The column gets
    /// the fold of every zone; each chunk gets the fold of the zones that exactly tile its rows, and
    /// keeps its array-level stats when zone boundaries cut through it.
    private static Node withZoneStats(Node column, List<Zone> zones) {
        if (zones.isEmpty()) {
            return column;
        }
        Node withChunks = withChunkZoneStats(column, zones);
        return new Node(withChunks.layout(), withChunks.fieldName(), withChunks.usedEncodings(),
                fold(zones), withChunks.children());
    }

    private static Node withChunkZoneStats(Node node, List<Zone> zones) {
        int data = dataChildIndex(node);
        if (!node.layout().isChunked() && data >= 0) {
            List<Node> children = new ArrayList<>(node.children());
            children.set(data, withChunkZoneStats(children.get(data), zones));
            return new Node(node.layout(), node.fieldName(), node.usedEncodings(), node.stats(),
                    List.copyOf(children));
        }
        List<Node> parts = node.layout().isChunked() ? node.children() : List.of(node);
        List<Node> updated = withTilingZones(parts, zones);
        if (!node.layout().isChunked()) {
            return updated.getFirst();
        }
        return new Node(node.layout(), node.fieldName(), node.usedEncodings(), node.stats(), updated);
    }

    /// Each part with its stats replaced by the fold of the zones exactly covering its rows, or
    /// unchanged when zone boundaries cut through it. Parts and zones are both in row order, so one
    /// cursor walks the zones once.
    private static List<Node> withTilingZones(List<Node> parts, List<Zone> zones) {
        List<Node> out = new ArrayList<>(parts.size());
        int z = 0;
        long firstRow = 0;
        for (Node part : parts) {
            long end = firstRow + part.layout().rowCount();
            while (z < zones.size() && zones.get(z).firstRow() < firstRow) {
                z++;
            }
            int from = z;
            while (z < zones.size() && zoneEnd(zones.get(z)) <= end) {
                z++;
            }
            boolean tiles = z > from && zones.get(from).firstRow() == firstRow && zoneEnd(zones.get(z - 1)) == end;
            out.add(tiles
                    ? new Node(part.layout(), part.fieldName(), part.usedEncodings(),
                            fold(zones.subList(from, z)), part.children())
                    : part);
            firstRow = end;
        }
        return List.copyOf(out);
    }

    private static long zoneEnd(Zone zone) {
        return zone.firstRow() + zone.rowCount();
    }

    private static ArrayStats fold(List<Zone> zones) {
        Object min = null;
        Object max = null;
        for (Zone zone : zones) {
            min = VortexInspector.pickMin(min, zone.stats().min());
            max = VortexInspector.pickMax(max, zone.stats().max());
        }
        if (min == null && max == null) {
            return ArrayStats.empty();
        }
        return new ArrayStats(min, max, null, null, null, null, null);
    }

    /// The chunks of a column: the children of the first chunked node on its data path, or the
    /// end of that path when nothing below it is chunked.
    ///
    /// @param column a top-level column node
    /// @return the column's chunks in row order
    static List<Node> chunkParts(Node column) {
        Node node = column;
        while (!node.layout().isChunked() && dataChildIndex(node) >= 0) {
            node = node.children().get(dataChildIndex(node));
        }
        return node.layout().isChunked() ? node.children() : List.of(node);
    }

    /// Index of the child carrying this node's rows, or `-1` when the node is a leaf or a shape
    /// whose rows do not live in one child. Mirrors the reader's own layout decoders: a
    /// [Layout#isZoned()] node wraps its data as `child[0]` and its zone-map table as `child[1]`,
    /// while a [Layout#isDict()] node stores `(values, codes)` and the codes are what carries the
    /// rows. Counting children alone gets both wrong.
    private static int dataChildIndex(Node node) {
        int children = node.children().size();
        if (node.layout().isZoned() && children > 0) {
            return 0;
        }
        if (node.layout().isDict() && children >= 2) {
            return 1;
        }
        return children == 1 ? 0 : -1;
    }

    private static Node buildNode(Layout layout, Optional<String> fieldName, VortexHandle handle,
            List<EncodingId> arraySpecs, Set<String> overallUsed, Map<Integer, String> segmentEncodings,
            Progress progress, int[] counter, int total) {
        Set<String> localUsed = new LinkedHashSet<>();
        ArrayStats stats = ArrayStats.empty();
        if (layout.isFlat() && !layout.segments().isEmpty()) {
            int segIdx = layout.segments().getFirst();
            SegmentSpec spec = handle.footer().segmentSpecs().get(segIdx);
            if (spec.compression().code == 0) {
                MemorySegment seg = handle.rawSegment(spec);
                Peek peek = peekFlatRoot(seg, arraySpecs);
                if (peek.encoding() != null) {
                    localUsed.addAll(peek.nestedEncodings());
                    overallUsed.addAll(peek.nestedEncodings());
                    segmentEncodings.put(segIdx, peek.encoding());
                }
                stats = peek.stats();
                counter[0]++;
                progress.update(counter[0], total);
            }
        }
        List<Node> children = new ArrayList<>(layout.children().size());
        for (Layout child : layout.children()) {
            Node n = buildNode(child, Optional.empty(), handle, arraySpecs, overallUsed,
                    segmentEncodings, progress, counter, total);
            localUsed.addAll(n.usedEncodings());
            children.add(n);
        }
        return new Node(layout, fieldName, Set.copyOf(localUsed), stats, List.copyOf(children));
    }

    private static int countPeekableSegments(Layout layout, Footer footer) {
        int n = 0;
        if (layout.isFlat() && !layout.segments().isEmpty()) {
            SegmentSpec spec = footer.segmentSpecs().get(layout.segments().getFirst());
            if (spec.compression().code == 0) {
                n++;
            }
        }
        for (Layout child : layout.children()) {
            n += countPeekableSegments(child, footer);
        }
        return n;
    }

    /// Callback used by [#build(VortexHandle, Progress)] to report how many
    /// flat segments have been peeked so far. Implementations may render a
    /// progress bar, log, or ignore (see [#NOOP]).
    @FunctionalInterface
    public interface Progress {
        /// Sink that discards updates.
        Progress NOOP = (current, total) -> {
        };

        /// Reports progress.
        ///
        /// @param current number of segments peeked so far
        /// @param total   total peekable segments in the file
        void update(int current, int total);
    }

    private static Peek peekFlatRoot(MemorySegment seg, List<EncodingId> arraySpecs) {
        int segLen = (int) seg.byteSize();
        int fbLen = seg.get(LE_INT, segLen - 4L);
        long fbStart = segLen - 4L - fbLen;
        FbsArray fbArray = FbsArray.getRootAsFbsArray(seg.asSlice(fbStart, fbLen));
        FbsArrayNode root = fbArray.root();
        if (root == null) {
            return new Peek(null, ArrayStats.empty(), Set.of());
        }
        Set<String> nested = new LinkedHashSet<>();
        collectEncodings(root, arraySpecs, nested, 0, new int[]{0});
        return new Peek(resolveEncodingId(arraySpecs, root.encoding()), ArrayStats.fromFbs(root.stats()),
                Set.copyOf(nested));
    }

    /// Hard cap on the `ArrayNode`-tree walk depth in [#collectEncodings(FbsArrayNode, List, Set, int, int[])],
    /// mirroring `SerializedArrayDecoder.MAX_ARRAY_TREE_DEPTH` in the reader module: a crafted segment with
    /// deeply nested children could otherwise drive unbounded recursion into a `StackOverflowError`,
    /// an `Error` that would bypass the [VortexException] contract for malformed untrusted input.
    static final int MAX_ENCODING_TREE_DEPTH = 64;

    /// Hard cap on the total number of `ArrayNode`s visited by one
    /// [#collectEncodings(FbsArrayNode, List, Set, int, int[])] walk. The depth cap alone is not enough: nothing
    /// stops two sibling `children` vector slots from resolving to the exact same absolute child position (the
    /// underlying FlatBuffers `uoffset` mechanism is not validated for aliasing), so a crafted segment with a
    /// handful of levels, each fanning out to two children that both alias the next level's single node, drives
    /// the walk to an exponential (up to 2^[#MAX_ENCODING_TREE_DEPTH]) number of visits while depth itself
    /// never exceeds the limit.
    static final int MAX_ENCODING_TREE_NODES = 4096;

    /// Resolves a wire-supplied encoding index against the footer's array spec table.
    ///
    /// @param arraySpecs footer's array spec table
    /// @param encIdx     encoding index read off the wire ([FbsArrayNode#encoding()])
    /// @return the resolved encoding id
    /// @throws VortexException if `encIdx` is out of bounds for `arraySpecs`
    private static String resolveEncodingId(List<EncodingId> arraySpecs, int encIdx) {
        if (encIdx < 0 || encIdx >= arraySpecs.size()) {
            throw new VortexException("array node encoding index " + encIdx
                    + " out of bounds (arraySpecs.size=" + arraySpecs.size() + ")");
        }
        return arraySpecs.get(encIdx).id();
    }

    /// Walks a Flat segment's full `ArrayNode` tree, accumulating the encoding
    /// id of the root and every descendant (e.g. an FSST child nested under a
    /// Masked root) - not just the root's own encoding.
    ///
    /// @param node       array node to visit
    /// @param arraySpecs footer's array spec table, indexed by [FbsArrayNode#encoding()]
    /// @param into       accumulator for resolved encoding ids
    /// @param depth      current recursion depth, starting at `0` for the root
    /// @param visited    single-element counter of nodes visited so far across the whole walk
    private static void collectEncodings(FbsArrayNode node, List<EncodingId> arraySpecs, Set<String> into,
            int depth, int[] visited) {
        if (depth > MAX_ENCODING_TREE_DEPTH) {
            throw new VortexException("array tree depth exceeds limit (" + MAX_ENCODING_TREE_DEPTH + ")");
        }
        if (++visited[0] > MAX_ENCODING_TREE_NODES) {
            throw new VortexException("array tree node count exceeds limit (" + MAX_ENCODING_TREE_NODES + ")");
        }
        into.add(resolveEncodingId(arraySpecs, node.encoding()));
        int childCount = node.childrenLength();
        for (int i = 0; i < childCount; i++) {
            collectEncodings(node.children(i), arraySpecs, into, depth + 1, visited);
        }
    }

    /// Result of a single Flat segment peek - the resolved root encoding id
    /// (or `null` when the FlatBuffer carried no root), the per-array
    /// statistics decoded from the same FlatBuffer, and every encoding id
    /// found anywhere in the segment's `ArrayNode` tree (root plus nested
    /// children, e.g. an FSST child of a Masked root).
    ///
    /// @param encoding        resolved root encoding id from the array spec table, or `null`
    /// @param stats           per-array stats, or [ArrayStats#empty()] if unknown
    /// @param nestedEncodings every encoding id in the segment's `ArrayNode` tree, root included;
    ///                        empty when `encoding` is `null`
    public record Peek(String encoding, ArrayStats stats, Set<String> nestedEncodings) {
        /// Sentinel returned for non-Flat nodes, compressed segments, or
        /// segments that don't carry an array root.
        public static final Peek EMPTY = new Peek(null, ArrayStats.empty(), Set.of());
    }
}
