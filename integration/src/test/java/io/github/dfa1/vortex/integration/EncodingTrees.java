package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.fbs.FbsArray;
import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.core.fbs.FbsArrayStats;
import io.github.dfa1.vortex.core.fbs.FbsPrecision;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.layout.Layout;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;

/// A file's layouts and array encodings, for comparing what vortex-jni and vortex-java write.
final class EncodingTrees {

    private EncodingTrees() {
    }

    /// The file's layouts and array encodings, depth first, one indented id a line. A chunked
    /// layout around a single chunk is left out: Java keeps one where Rust writes the flat layout
    /// directly, and that layout shape is not what the encoding comparisons test.
    static List<String> of(Path file) throws IOException {
        return of(file, false);
    }

    /// [#of(Path)] with the array stats each node carries after its id, as `[min max null_count ..]`
    /// naming the stats present and their precision: what Rust and Java write into a flat segment.
    static List<String> withStats(Path file) throws IOException {
        return of(file, true);
    }

    private static List<String> of(Path file, boolean stats) throws IOException {
        List<String> out = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file)) {
            layoutTree(reader, reader.layout(), 0, out, stats);
        }
        return out;
    }

    private static void layoutTree(VortexReader reader, Layout layout, int depth, List<String> out, boolean stats) {
        if (layout.isChunked() && layout.children().size() == 1) {
            layoutTree(reader, layout.children().getFirst(), depth, out, stats);
            return;
        }
        out.add("  ".repeat(depth) + layout.layoutId().id());
        if (layout.isFlat() && !layout.segments().isEmpty()) {
            SegmentSpec spec = reader.footer().segmentSpecs().get(layout.segments().getFirst());
            MemorySegment seg = reader.rawSegment(spec);
            int fbLen = seg.get(LE_INT, seg.byteSize() - 4L);
            FbsArray array = FbsArray.getRootAsFbsArray(seg.asSlice(seg.byteSize() - 4L - fbLen, fbLen));
            arrayTree(array.root(), reader.footer().arraySpecs(), depth + 1, out, stats);
            return;
        }
        for (Layout child : layout.children()) {
            layoutTree(reader, child, depth + 1, out, stats);
        }
    }

    private static void arrayTree(FbsArrayNode node, List<EncodingId> specs, int depth, List<String> out,
                                  boolean stats) {
        String line = "  ".repeat(depth) + specs.get(node.encoding()).id();
        if (stats) {
            line += " " + statNames(node.stats());
        }
        out.add(line);
        for (int i = 0; i < node.childrenLength(); i++) {
            arrayTree(node.children(i), specs, depth + 1, out, stats);
        }
    }

    /// The stats a node records, by Rust's names, with their values: bounds as hex of the scalar
    /// bytes (`~` after the name when inexact), flags and counts as numbers.
    private static String statNames(FbsArrayStats stats) {
        if (stats == null) {
            return "[]";
        }
        List<String> names = new ArrayList<>();
        if (stats.hasMin()) {
            names.add("min" + (stats.minPrecision() == FbsPrecision.Exact ? "" : "~") + "=" + hex(stats.minAsSegment()));
        }
        if (stats.hasMax()) {
            names.add("max" + (stats.maxPrecision() == FbsPrecision.Exact ? "" : "~") + "=" + hex(stats.maxAsSegment()));
        }
        if (stats.hasSum()) {
            names.add("sum=" + hex(stats.sumAsSegment()));
        }
        if (stats.hasIsConstant()) {
            names.add("is_constant=" + stats.isConstant());
        }
        if (stats.hasIsSorted()) {
            names.add("is_sorted=" + stats.isSorted());
        }
        if (stats.hasIsStrictSorted()) {
            names.add("is_strict_sorted=" + stats.isStrictSorted());
        }
        if (stats.hasNullCount()) {
            names.add("null_count=" + stats.nullCount());
        }
        if (stats.hasNanCount()) {
            names.add("nan_count=" + stats.nanCount());
        }
        if (stats.hasUncompressedSizeInBytes()) {
            names.add("uncompressed_size=" + stats.uncompressedSizeInBytes());
        }
        return names.toString();
    }

    private static String hex(java.lang.foreign.MemorySegment segment) {
        return java.util.HexFormat.of().formatHex(segment.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
    }
}
