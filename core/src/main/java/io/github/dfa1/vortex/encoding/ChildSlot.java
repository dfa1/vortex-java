package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;

/// An open slot in a partially-assembled encoding tree.
/// The cascading compressor fills each slot recursively, then splices the result
/// into the parent node's children array at {@code parentChildIdx}.
public record ChildSlot(DType childDtype, Object childData, int parentChildIdx) {
}
