package io.github.dfa1.vortex.core;

import java.util.List;

/// Parsed file footer. Contains the dictionaries needed to resolve indices in the layout tree.
///
/// All spec lists are index-stable: array/layout/segment indices in the tree refer
/// into these lists by position.
///
/// @param arraySpecs       encoding id strings indexed by array spec index
/// @param layoutSpecs      layout type strings indexed by layout spec index
/// @param segmentSpecs     segment byte ranges indexed by segment index
/// @param compressionSpecs compression schemes indexed by compression spec index
public record Footer(
        List<String> arraySpecs,
        List<String> layoutSpecs,
        List<SegmentSpec> segmentSpecs,
        List<CompressionScheme> compressionSpecs
) {
}
