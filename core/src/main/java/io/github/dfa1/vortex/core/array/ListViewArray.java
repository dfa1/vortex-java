package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

/// Decoded variable-length list-view array (Arrow ListView layout).
///
/// <p>Unlike {@link ListArray}, offsets and sizes are independent per row:
/// {@code list[i] = elements[offsets[i] .. offsets[i] + sizes[i]]}.
/// Both {@code offsets} and {@code sizes} have length {@code outerLen} (not outerLen+1).
public final class ListViewArray implements Array {

	private final DType.List dtype;
	private final long outerLen;
	private final Array elements;
	private final Array offsets;
	private final Array sizes;

	public ListViewArray(DType.List dtype, long outerLen, Array elements, Array offsets, Array sizes) {
		this.dtype = dtype;
		this.outerLen = outerLen;
		this.elements = elements;
		this.offsets = offsets;
		this.sizes = sizes;
	}

	@Override
	public long length() {
		return outerLen;
	}

	@Override
	public DType dtype() {
		return dtype;
	}

	public Array elements() {
		return elements;
	}

	public Array offsets() {
		return offsets;
	}

	public Array sizes() {
		return sizes;
	}

	@Override
	public Array child(int i) {
		return switch (i) {
			case 0 -> elements;
			case 1 -> offsets;
			case 2 -> sizes;
			default -> throw new VortexException("ListViewArray child index out of bounds: " + i);
		};
	}
}
