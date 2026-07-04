package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.reader.array.Array;

import java.util.Set;

/// Built-in decoder for the zone-map layout — both the canonical `vortex.zoned` and its legacy
/// `vortex.stats` alias wrap the data layout as `child[0]`; decode passes straight through to it,
/// the per-zone stats being a pruning optimization handled elsewhere. Extracted verbatim from the
/// zoned branch of `ScanIterator.decodeLayout`.
final class ZonedLayoutDecoder implements LayoutDecoder {

    @Override
    public LayoutId layoutId() {
        return LayoutId.ZONED;
    }

    @Override
    public Set<LayoutId> layoutIds() {
        return Set.of(LayoutId.ZONED, LayoutId.STATS);
    }

    @Override
    public Array decode(LayoutDecodeContext ctx, Layout layout, DType dtype) {
        // A zoned node with no children is not a decodable shape — fail loudly with the same
        // message the fall-through path produced when the original dispatch matched nothing.
        if (layout.children().isEmpty()) {
            throw new VortexException("cannot decode layout " + layout.layoutId());
        }
        return ctx.decodeChild(layout.children().getFirst(), dtype);
    }
}
