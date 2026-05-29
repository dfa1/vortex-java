package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/// Concrete [Array] for F32 primitive columns. Hoists a single `MemorySegment`,
/// length, and a `static final` LE float layout for JIT constant-folding.
public final class FloatArray implements Array {

	private static final ValueLayout.OfFloat LAYOUT =
			ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private final DType dtype;
	private final long length;
	private final MemorySegment buffer;
	private final ArrayStats stats;

	public FloatArray(DType dtype, long length, MemorySegment buffer, ArrayStats stats) {
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
	public float getFloat(long i) {
		return buffer.getAtIndex(LAYOUT, i);
	}

	@Override
	public double getDouble(long i) {
		return buffer.getAtIndex(LAYOUT, i);
	}
}
