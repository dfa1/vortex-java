package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;

import java.util.Set;

/// An open slot in a partially-assembled encoding tree.
/// The cascading compressor fills each slot recursively, then splices the result
/// into the parent node's children array at `parentChildIdx`.
///
/// The slot also declares which encodings may not compete for it. Exclusions belong here rather
/// than on the parent encoder because they differ per child of the same encoding: a dict's codes
/// child rules out `vortex.sequence` (an index sequence over codes buys indirection, not
/// compression) while its values pool does not. [CascadingCompressor] unions these into the child
/// context's exclusion set, so an ancestor's exclusions still apply.
///
/// Every encoding that opens a slot names itself here — recursion into the same encoding is the
/// exclusion this replaces, previously applied generically by the compressor.
///
/// @param childDtype     logical type of the child data
/// @param childData      the raw child data to be encoded (type depends on the child encoding)
/// @param parentChildIdx index in the parent node's children array where the result will be placed
/// @param excluded       encoding ids barred from competing for this child
public record ChildSlot(DType childDtype, Object childData, int parentChildIdx, Set<EncodingId> excluded) {

    /// Normalizes `excluded` to an immutable copy.
    public ChildSlot {
        excluded = Set.copyOf(excluded);
    }
}
