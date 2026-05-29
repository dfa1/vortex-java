package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;

/// Zero-length [Array] returned by [Array#empty]. Has no buffers or children.
public final class EmptyArray implements Array {

	private final DType dtype;

	public EmptyArray(DType dtype) {
		this.dtype = dtype;
	}

	@Override
	public DType dtype() {
		return dtype;
	}

	@Override
	public long length() {
		return 0;
	}

	@Override
	public ArrayStats stats() {
		return ArrayStats.empty();
	}
}
