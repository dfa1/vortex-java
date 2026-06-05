package io.github.dfa1.vortex.encoding;

import java.lang.foreign.MemorySegment;
import java.util.List;

/// One step in cascade-aware encoding: a partially-assembled node tree plus open child slots.
///
/// <p>Terminal steps have no open children and carry a fully-resolved {@link EncodeResult}.
/// Intermediate steps have open children that the {@link CascadingCompressor} recursively fills.
///
/// <p>Buffer layout: {@code ownedBuffers} holds buffers belonging to the partial root
/// (e.g. patch index/value buffers for ALP). Child recursion results are appended after these;
/// child {@code bufferIndices} are remapped by {@code +ownedBuffers.size()}.
///
/// <p>When {@code applicable} is false the encoding cannot handle this data; {@link #ownedBytes()}
/// returns {@link Long#MAX_VALUE}/2 so the step never wins in size-based selection.
public record CascadeStep(
		EncodeNode partialRoot,
		List<MemorySegment> ownedBuffers,
		List<ChildSlot> openChildren,
		byte[] statsMin,
		byte[] statsMax,
		boolean applicable
) {
	/// Convenience: terminal step — no open children, result is final.
	public static CascadeStep terminal(EncodeResult result) {
		return new CascadeStep(result.rootNode(), result.buffers(), List.of(),
				result.statsMin(), result.statsMax(), true);
	}

	/// Sentinel: encoding cannot handle this data — never wins in size selection.
	public static CascadeStep notApplicable() {
		return new CascadeStep(null, List.of(), List.of(), null, null, false);
	}

	public boolean isTerminal() {
		return openChildren.isEmpty();
	}

	/// Total byte size of owned buffers (used for size-based winner selection on samples).
	/// Returns {@link Long#MAX_VALUE}/2 for non-applicable steps.
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
