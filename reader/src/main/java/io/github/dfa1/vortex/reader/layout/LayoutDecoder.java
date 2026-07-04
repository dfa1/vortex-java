package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.reader.array.Array;

import java.util.Set;

/// SPI for decoding one kind of layout node into an [Array]. Registered on a [LayoutRegistry],
/// mirroring how [io.github.dfa1.vortex.reader.decode.EncodingDecoder] plugs into the encoding
/// registry.
///
/// Scope — this SPI covers full-column subtree decode only. Zone-map pruning, filtered scans,
/// and chunk iteration recognize the built-in layouts (`flat`, `chunked`, `zoned`/`stats`,
/// `dict`) directly and are not extension points; a custom layout decodes as a whole column but
/// does not participate in those optimizations.
public interface LayoutDecoder {

    /// Returns the canonical layout id this decoder handles.
    ///
    /// @return the primary [LayoutId] of this decoder
    LayoutId layoutId();

    /// Returns every layout id this decoder handles. Defaults to just [#layoutId()]; a decoder
    /// serving a wire alias (e.g. the zoned layout under both `vortex.zoned` and its legacy
    /// `vortex.stats` id) overrides this to return the full set. The [LayoutRegistry] registers
    /// the decoder under each id in this set.
    ///
    /// @return the set of layout ids this decoder handles; never empty
    default Set<LayoutId> layoutIds() {
        return Set.of(layoutId());
    }

    /// Decodes the given layout node into an [Array] of `dtype`, using `ctx` for child recursion,
    /// flat-segment reads, and allocation.
    ///
    /// @param ctx    the decode context bound to the current chunk epoch
    /// @param layout the layout node to decode
    /// @param dtype  logical type the layout decodes to
    /// @return the decoded [Array]
    Array decode(LayoutDecodeContext ctx, Layout layout, DType dtype);
}
