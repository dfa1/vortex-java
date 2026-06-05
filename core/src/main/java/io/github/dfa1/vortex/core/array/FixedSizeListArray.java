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

	/// Constructs a {@code FixedSizeListArray} from a flat elements array.
	///
	/// @param dtype    logical fixed-size list type (provides element type and fixed size)
	/// @param outerLen number of outer list elements
	/// @param elements flat array of {@code outerLen * fixedSize} element values
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

	/// Returns per-array statistics (always empty for fixed-size list arrays).
	///
	/// @return empty array statistics
	public ArrayStats stats() {
		return ArrayStats.empty();
	}

	/// Returns the flat elements array containing {@code outerLen * fixedSize} values.
	///
	/// @return the flat elements array
	public Array elements() {
		return elements;
	}

	/// Returns the fixed number of elements per outer list entry.
	///
	/// @return the fixed size from the dtype
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
