package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;

/// Zero-length [Array]. Has no buffers or children.
public record EmptyArray(DType dtype) implements Array {

	@Override
	public long length() {
		return 0;
	}

	public ArrayStats stats() {
		return ArrayStats.empty();
	}

	public static Array of(DType dtype) {
		return new EmptyArray(dtype);
	}
}
