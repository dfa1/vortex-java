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
        if (layout.segments().isEmpty()) {
            throw new VortexException("no segments");
        }
        int segIdx = layout.segments().getFirst();
        SegmentSpec spec = ctx.segmentSpec(segIdx);
        return ctx.decodeSegment(spec, dtype, layout.rowCount());
    }
}
