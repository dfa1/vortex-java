package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;

/// Concrete [Array] for all-null columns ({@code DType.Null}). Holds only a row count.
///
/// @param dtype  the null dtype
/// @param length number of null elements
public record NullArray(DType dtype, long length) implements Array {


	/// Returns per-array statistics (always empty for null arrays).
	///
	/// @return empty array statistics
	public ArrayStats stats() {
		return ArrayStats.empty();
	}
}
