package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;

/// Zero-length [Array] returned by [Array#empty]. Has no buffers or children.
public record EmptyArray(DType dtype) implements Array {

	@Override
	public long length() {
		return 0;
	}

	@Override
	public ArrayStats stats() {
		return ArrayStats.empty();
	}
}
