package io.github.dfa1.vortex.inspect;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;

import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Footer;
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.core.fbs.FbsArray;
import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.reader.VortexHandle;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
/// @param root             root layout node
public record InspectorTree(
        int version,
        long fileSize,
        DType dtype,
        List<String> registeredEncodings,
        Set<String> usedEncodings,
        List<SegmentSpec> segmentSpecs,
        long totalRowCount,
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
    /// @param stats          per-array statistics decoded from the segment's FlatBuffer
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
        List<String> colNames = (dtype instanceof DType.Struct s) ? s.fieldNames() : List.of();
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
                footer.arraySpecs(),
                Set.of(),
                footer.segmentSpecs(),
                layout.rowCount(),
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

        List<String> colNames = (dtype instanceof DType.Struct s) ? s.fieldNames() : List.of();
        Set<String> overallUsed = new LinkedHashSet<>();
        Node root = buildNode(layout, Optional.empty(), handle, footer.arraySpecs(),
                overallUsed, progress, counter, total);
        if (layout.isStruct()) {
            List<Node> namedChildren = new ArrayList<>(root.children().size());
            for (int i = 0; i < root.children().size(); i++) {
                Node child = root.children().get(i);
                String name = i < colNames.size() ? colNames.get(i) : "col" + i;
                namedChildren.add(new Node(child.layout(), Optional.of(name),
                        child.usedEncodings(), child.stats(), child.children()));
            }
            root = new Node(root.layout(), Optional.empty(), root.usedEncodings(),
                    root.stats(), List.copyOf(namedChildren));
        }

        return new InspectorTree(
                handle.version(),
                handle.fileSize(),
                dtype,
                footer.arraySpecs(),
                Set.copyOf(overallUsed),
                footer.segmentSpecs(),
                layout.rowCount(),
                root);
    }

    private static Node buildNode(Layout layout, Optional<String> fieldName, VortexHandle handle,
            List<String> arraySpecs, Set<String> overallUsed,
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
                    localUsed.add(peek.encoding());
                    overallUsed.add(peek.encoding());
                }
                stats = peek.stats();
                counter[0]++;
                progress.update(counter[0], total);
            }
        }
        List<Node> children = new ArrayList<>(layout.children().size());
        for (Layout child : layout.children()) {
            Node n = buildNode(child, Optional.empty(), handle, arraySpecs, overallUsed,
                    progress, counter, total);
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

    private static Peek peekFlatRoot(MemorySegment seg, List<String> arraySpecs) {
        int segLen = (int) seg.byteSize();
        int fbLen = seg.get(LE_INT, segLen - 4L);
        long fbStart = segLen - 4L - fbLen;
        FbsArray fbArray = FbsArray.getRootAsFbsArray(seg.asSlice(fbStart, fbLen));
        FbsArrayNode root = fbArray.root();
        if (root == null) {
            return new Peek(null, ArrayStats.empty());
        }
        return new Peek(arraySpecs.get(root.encoding()), ArrayStats.fromFbs(root.stats()));
    }

    /// Result of a single Flat segment peek - the resolved encoding id (or
    /// `null` when the FlatBuffer carried no root) plus the per-array
    /// statistics decoded from the same FlatBuffer.
    ///
    /// @param encoding resolved encoding id from the array spec table, or `null`
    /// @param stats    per-array stats, or [ArrayStats#empty()] if unknown
    public record Peek(String encoding, ArrayStats stats) {
        /// Sentinel returned for non-Flat nodes, compressed segments, or
        /// segments that don't carry an array root.
        public static final Peek EMPTY = new Peek(null, ArrayStats.empty());
    }
}
