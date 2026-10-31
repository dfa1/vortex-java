package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.fbs.FbsArray;
import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.layout.Layout;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;

/// A file's layouts and array encodings, for comparing what vortex-jni and vortex-java write, and
/// whether it holds a dict layout.
final class EncodingTrees {

    private EncodingTrees() {
    }

    /// The file's layouts and array encodings, depth first, one indented id a line. A chunked
    /// layout around a single chunk is left out: Java keeps one where Rust writes the flat layout
    /// directly, and that layout shape is not what the encoding comparisons test.
    static List<String> of(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file)) {
            layoutTree(reader, reader.layout(), 0, out);
        }
        return out;
    }

    /// Whether the file holds a dict layout anywhere in its layout tree.
    static boolean hasDictLayout(Path file) throws IOException {
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            return hasDictLayout(InspectorTree.build(reader).root());
        }
    }

    /// Whether `node` or any node below it is a dict layout.
    static boolean hasDictLayout(InspectorTree.Node node) {
        if (node.layout().isDict()) {
            return true;
        }
        return node.children().stream().anyMatch(EncodingTrees::hasDictLayout);
    }

    private static void layoutTree(VortexReader reader, Layout layout, int depth, List<String> out) {
        if (layout.isChunked() && layout.children().size() == 1) {
            layoutTree(reader, layout.children().getFirst(), depth, out);
            return;
        }
        out.add("  ".repeat(depth) + layout.layoutId().id());
        if (layout.isFlat() && !layout.segments().isEmpty()) {
            SegmentSpec spec = reader.footer().segmentSpecs().get(layout.segments().getFirst());
            MemorySegment seg = reader.rawSegment(spec);
            int fbLen = seg.get(LE_INT, seg.byteSize() - 4L);
            FbsArray array = FbsArray.getRootAsFbsArray(seg.asSlice(seg.byteSize() - 4L - fbLen, fbLen));
            arrayTree(array.root(), reader.footer().arraySpecs(), depth + 1, out);
            return;
        }
        for (Layout child : layout.children()) {
            layoutTree(reader, child, depth + 1, out);
        }
    }

    private static void arrayTree(FbsArrayNode node, List<EncodingId> specs, int depth, List<String> out) {
        out.add("  ".repeat(depth) + specs.get(node.encoding()).id());
        for (int i = 0; i < node.childrenLength(); i++) {
            arrayTree(node.children(i), specs, depth + 1, out);
        }
    }
}
