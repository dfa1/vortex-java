package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ChunkedArrayCombiner;

import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/// Built-in decoder for the `vortex.chunked` layout — a sequence of flat leaves decoded into the
/// zero-copy `ChunkedXxxArray` shape (ADR 0012). Extracted verbatim from `ScanIterator`'s
/// `collectFlats` + `decodeChunkedLayout`.
final class ChunkedLayoutDecoder implements LayoutDecoder {

    @Override
    public LayoutId layoutId() {
        return LayoutId.CHUNKED;
    }

    @Override
    public Array decode(LayoutDecodeContext ctx, Layout layout, DType dtype) {
        var flats = new ArrayList<Layout>();
        collectFlats(layout, flats);
        return decodeChunkedLayout(ctx, flats, dtype, layout.rowCount());
    }

    /// Flattens a layout subtree into its ordered flat (and dict) leaves. A private copy of
    /// `ScanIterator.collectFlats`: the scan keeps its own for chunk-shape planning, while the
    /// chunked decoder needs the same flattening to build the leaf array list.
    ///
    /// @param layout the layout subtree to flatten
    /// @param out    accumulator for the flat leaves in scan order
    private static void collectFlats(Layout layout, List<Layout> out) {
        if (layout.isFlat()) {
            out.add(layout);
        } else if (layout.isDict()) {
            // Dict layout is a leaf chunk — decoded as a unit (values + codes).
            out.add(layout);
        } else if (layout.isZoned()) {
            // vortex.stats wraps one child (the data layout) — pass through for data
            if (!layout.children().isEmpty()) {
                collectFlats(layout.children().getFirst(), out);
            }
        } else if (layout.isChunked()) {
            // metadata[0] == 1 means children[0] is the per-chunk stats layout; skip it
            int start = (layout.metadata() != null
                                 && layout.metadata().byteSize() > 0
                                 && layout.metadata().get(ValueLayout.JAVA_BYTE, 0) == 1) ? 1 : 0;
            for (int i = start; i < layout.children().size(); i++) {
                collectFlats(layout.children().get(i), out);
            }
        }
    }

    private static Array decodeChunkedLayout(LayoutDecodeContext ctx, List<Layout> flats, DType dtype,
            long totalRows) {
        if (flats.isEmpty()) {
            throw new VortexException(EncodingId.VORTEX_CHUNKED, "no flat children");
        }
        if (flats.size() == 1) {
            return ctx.decodeChild(flats.getFirst(), dtype);
        }
        // ADR 0012: every ptype gets the zero-copy ChunkedXxxArray shape. The concat path is gone.
        var chunkArrays = new ArrayList<Array>(flats.size());
        for (Layout flat : flats) {
            // Registry dispatch, not a direct decodeFlat call — a custom decoder registered for
            // a leaf's layout id must be honored under a chunked parent too.
            chunkArrays.add(ctx.decodeChild(flat, dtype));
        }
        // ChunkedArrayCombiner dispatches on dtype and fails with a VortexException — never a raw
        // ClassCastException — for any dtype without a chunked shape (issue #268). It also stitches
        // list columns into a single ListArray and recovers the per-chunk validity that the
        // ChunkedXxxArray.of constructors drop when flattening masked chunks, so a nullable chunked
        // column keeps its nulls.
        return ChunkedArrayCombiner.combine(dtype, totalRows, chunkArrays, ctx.arena());
    }
}
