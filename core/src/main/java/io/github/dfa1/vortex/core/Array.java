package io.github.dfa1.vortex.core;


import java.lang.foreign.MemorySegment;

/// Decoded columnar data. Buffers are `MemorySegment` slices backed by the
/// memory-mapped file; lifetime is tied to the `VortexFile`'s Arena.
public record Array(
		DType dtype,
		long length,
		MemorySegment[] buffers,
		Array[] children,
		ArrayStats stats
) {
	public static final Array[] NO_CHILDREN = new Array[0];

	public static Array empty(DType dtype) {
		return new Array(dtype, 0, new MemorySegment[0], NO_CHILDREN, ArrayStats.empty());
	}

	public MemorySegment buffer(int i) {
		return buffers[i];
	}

	public Array child(int i) {
		return children[i];
	}
}
