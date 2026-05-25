package io.github.dfa1.vortex.core;

import java.util.List;

/// Parsed file footer. Contains the dictionaries needed to resolve indices in the layout tree.
///
/// All spec lists are index-stable: array/layout/segment indices in the tree refer
/// into these lists by position.
public record Footer(
    List<String>            arraySpecs,
    List<String>            layoutSpecs,
    List<SegmentSpec>       segmentSpecs,
    List<CompressionScheme> compressionSpecs
) {}
