package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Concrete [Array] for bit-packed boolean columns (LSB-first, one byte per 8 elements).
public final class BoolArray implements Array {

	private final DType dtype;
	private final long length;
	private final MemorySegment buffer;
	private final ArrayStats stats;

	public BoolArray(DType dtype, long length, MemorySegment buffer, ArrayStats stats) {
		this.dtype = dtype;
		this.length = length;
		this.buffer = buffer;
		this.stats = stats;
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
		return stats;
	}

	@Override
	public MemorySegment buffer(int i) {
		if (i != 0) {
			throw new IndexOutOfBoundsException(i);
		}
		return buffer;
	}

	@Override
	public boolean getBoolean(long i) {
		byte b = buffer.get(ValueLayout.JAVA_BYTE, i >>> 3);
		return (b & (1 << (i & 7))) != 0;
	}
}
