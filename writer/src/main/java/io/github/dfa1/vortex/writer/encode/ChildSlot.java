package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;

/// An open slot in a partially-assembled encoding tree.
/// The cascading compressor fills each slot recursively, then splices the result
/// into the parent node's children array at `parentChildIdx`.
///
/// @param childDtype     logical type of the child data
/// @param childData      the raw child data to be encoded (type depends on the child encoding)
/// @param parentChildIdx index in the parent node's children array where the result will be placed
public record ChildSlot(DType childDtype, Object childData, int parentChildIdx) {
}
