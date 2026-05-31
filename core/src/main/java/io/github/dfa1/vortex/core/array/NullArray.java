package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;

/// Concrete [Array] for all-null columns ({@code DType.Null}). Holds only a row count.
public record NullArray(DType dtype, long length) implements Array {


	public ArrayStats stats() {
		return ArrayStats.empty();
	}
}
