package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.LayoutId;

import java.util.List;

/// Parsed file footer. Contains the dictionaries needed to resolve indices in the layout tree.
///
/// All spec lists are index-stable: array/layout/segment indices in the tree refer
/// into these lists by position. The wire strings are parsed to their typed ids once here,
/// at the parse boundary, so array/layout nodes index directly into typed dictionaries.
///
/// @param arraySpecs       [EncodingId] indexed by array spec index
/// @param layoutSpecs      [LayoutId] indexed by layout spec index
/// @param segmentSpecs     segment byte ranges indexed by segment index
/// @param compressionSpecs compression schemes indexed by compression spec index
public record Footer(
        List<EncodingId> arraySpecs,
        List<LayoutId> layoutSpecs,
        List<SegmentSpec> segmentSpecs,
        List<CompressionScheme> compressionSpecs
) {
}
