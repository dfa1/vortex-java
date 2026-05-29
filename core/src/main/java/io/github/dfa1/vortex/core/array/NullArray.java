package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;

/// Concrete [Array] for all-null columns ({@code DType.Null}). Holds only a row count.
public final class NullArray implements Array {

	private final DType dtype;
	private final long length;

	public NullArray(DType dtype, long length) {
		this.dtype = dtype;
		this.length = length;
	}

	@Override
	public DType dtype() {
		return dtype;
	}

	@Override
	public long length() {
		return length;
	}

	@Override
	public ArrayStats stats() {
		return ArrayStats.empty();
	}
}
