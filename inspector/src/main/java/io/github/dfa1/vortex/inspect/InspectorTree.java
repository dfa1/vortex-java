package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Footer;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.SegmentSpec;
import io.github.dfa1.vortex.fbs.Array;
import io.github.dfa1.vortex.fbs.ArrayNode;
import io.github.dfa1.vortex.io.VortexHandle;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    /// Builds an inspector tree from an open Vortex file handle, reporting
    /// progress on each Flat-segment peek (which on remote-storage handles
    /// triggers a separate HTTP range request).
    ///
    /// @param handle   open file handle
    /// @param progress progress sink receiving {@code (current, total)} after each segment peek
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
                MemorySegment seg = handle.slice(spec.offset(), spec.length());
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
        ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        int fbLen = bb.getInt(segLen - 4);
        int fbStart = segLen - 4 - fbLen;
        ByteBuffer fbBuf = bb.slice(fbStart, fbLen).order(ByteOrder.LITTLE_ENDIAN);
        Array fbArray = Array.getRootAsArray(fbBuf);
        ArrayNode root = fbArray.root();
        if (root == null) {
            return new Peek(null, ArrayStats.empty());
        }
        return new Peek(arraySpecs.get(root.encoding()), ArrayStats.fromFbs(root.stats()));
    }

    private record Peek(String encoding, ArrayStats stats) {
    }
}
