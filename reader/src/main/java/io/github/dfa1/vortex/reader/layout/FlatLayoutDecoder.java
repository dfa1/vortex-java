package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.array.Array;

/// Built-in decoder for the `vortex.flat` layout — a single encoded segment. Extracted verbatim
/// from `ScanIterator.decodeFlat`.
final class FlatLayoutDecoder implements LayoutDecoder {

    @Override
    public LayoutId layoutId() {
        return LayoutId.FLAT;
    }

    @Override
    public Array decode(LayoutDecodeContext ctx, Layout layout, DType dtype) {
        return decodeFlat(ctx, layout, dtype);
    }

    /// Decodes one flat leaf: resolves its single segment index to a [SegmentSpec] and delegates
    /// to the file handle. Shared with [ChunkedLayoutDecoder], which decodes each of its collected
    /// leaves through exactly this path (not registry dispatch), preserving the original behavior.
    ///
    /// @param ctx   the decode context
    /// @param flat  the flat layout node
    /// @param dtype logical type of the decoded array
    /// @return the decoded [Array]
    static Array decodeFlat(LayoutDecodeContext ctx, Layout flat, DType dtype) {
        if (flat.segments().isEmpty()) {
            throw new VortexException("no segments");
        }
        int segIdx = flat.segments().getFirst();
        SegmentSpec spec = ctx.segmentSpec(segIdx);
        return ctx.decodeFlatSegment(spec, dtype, flat.rowCount());
    }
}
