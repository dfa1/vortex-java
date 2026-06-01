package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;

/// Decoded fixed-size list array: holds a flat elements [Array] of length {@code outerLen * fixedSize}.
///
/// <p>List {@code i} covers elements {@code [i*fixedSize, (i+1)*fixedSize)}.
public final class FixedSizeListArray implements Array {

	private final DType.FixedSizeList dtype;
	private final long outerLen;
	private final Array elements;

	public FixedSizeListArray(DType.FixedSizeList dtype, long outerLen, Array elements) {
		this.dtype = dtype;
		this.outerLen = outerLen;
		this.elements = elements;
	}

	@Override
	public long length() {
		return outerLen;
	}

	@Override
	public DType dtype() {
		return dtype;
	}

	public ArrayStats stats() {
		return ArrayStats.empty();
	}

	public Array elements() {
		return elements;
	}

	public int fixedSize() {
		return dtype.fixedSize();
	}

	@Override
	public Array child(int i) {
		if (i != 0) {
			throw new ArrayIndexOutOfBoundsException("FixedSizeListArray child index " + i);
		}
		return elements;
	}
}
