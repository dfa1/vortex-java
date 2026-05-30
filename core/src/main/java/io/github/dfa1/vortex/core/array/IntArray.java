package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;
import java.lang.foreign.MemorySegment;
import java.util.function.IntConsumer;

/// Concrete [Array] for I32/U32 primitive columns.
public final class IntArray implements Array {

	private final DType dtype;
	private final long length;
	private final MemorySegment buffer;
	private final ArrayStats stats;

	public IntArray(DType dtype, long length, MemorySegment buffer, ArrayStats stats) {
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

	public int getInt(long i) {
		return buffer.getAtIndex(PTypeIO.LE_INT, i);
	}

	public void forEachInt(IntConsumer c) {
		MemorySegment buf = buffer;
		long n = length;
		for (long i = 0; i < n; i++) {
			c.accept(buf.getAtIndex(PTypeIO.LE_INT, i));
		}
	}
}
