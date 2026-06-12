package io.github.dfa1.vortex.writer.encode;

import java.lang.foreign.MemorySegment;
import java.util.List;

/// One step in cascade-aware encoding: a partially-assembled node tree plus open child slots.
///
/// <p>Terminal steps have no open children and carry a fully-resolved {@link EncodeResult}.
/// Intermediate steps have open children that the {@code CascadingCompressor} (writer module) recursively fills.
///
/// <p>Buffer layout: {@code ownedBuffers} holds buffers belonging to the partial root
/// (e.g. patch index/value buffers for ALP). Child recursion results are appended after these;
/// child {@code bufferIndices} are remapped by {@code +ownedBuffers.size()}.
///
/// <p>When {@code applicable} is false the encoding cannot handle this data; {@link #ownedBytes()}
/// returns {@link Long#MAX_VALUE}/2 so the step never wins in size-based selection.
///
/// @param partialRoot  partially-assembled root encode node (may be {@code null} when not applicable)
/// @param ownedBuffers buffers owned directly by the root node, before child buffers are appended
/// @param openChildren child slots to be filled recursively by the cascading compressor
/// @param statsMin     serialised minimum stat bytes, or {@code null}
/// @param statsMax     serialised maximum stat bytes, or {@code null}
/// @param applicable   {@code false} if this encoding cannot handle the input data
public record CascadeStep(
        EncodeNode partialRoot,
        List<MemorySegment> ownedBuffers,
        List<ChildSlot> openChildren,
        byte[] statsMin,
        byte[] statsMax,
        boolean applicable
) {
    /// Convenience: terminal step — no open children, result is final.
    ///
    /// @param result the fully-resolved encode result to wrap
    /// @return a terminal {@link CascadeStep} backed by {@code result}
    public static CascadeStep terminal(EncodeResult result) {
        return new CascadeStep(result.rootNode(), result.buffers(), List.of(),
                result.statsMin(), result.statsMax(), true);
    }

    /// Sentinel: encoding cannot handle this data — never wins in size selection.
    ///
    /// @return a non-applicable {@link CascadeStep} sentinel
    public static CascadeStep notApplicable() {
        return new CascadeStep(null, List.of(), List.of(), null, null, false);
    }

    /// Returns {@code true} if this step has no open child slots.
    ///
    /// @return {@code true} if the step is terminal (no open children)
    public boolean isTerminal() {
        return openChildren.isEmpty();
    }

    /// Total byte size of owned buffers (used for size-based winner selection on samples).
    /// Returns {@link Long#MAX_VALUE}/2 for non-applicable steps.
    ///
    /// @return total size in bytes of all owned buffers, or {@link Long#MAX_VALUE}/2 if not applicable
    public long ownedBytes() {
        if (!applicable) {
            return Long.MAX_VALUE / 2;
        }
        long total = 0;
        for (MemorySegment seg : ownedBuffers) {
            total += seg.byteSize();
        }
        return total;
    }
}
