package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.function.IntConsumer;

/// Concrete [Array] for I32/U32 primitive columns. Hoists a single
/// `MemorySegment`, length, and a `static final` LE int layout so the JIT can
/// constant-fold the VarHandle and auto-vectorise hot loops.
public final class IntArray implements Array {

	private static final ValueLayout.OfInt LAYOUT =
			ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

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
		return buffer.getAtIndex(LAYOUT, i);
	}

	public void forEachInt(IntConsumer c) {
		MemorySegment buf = buffer;
		long n = length;
		for (long i = 0; i < n; i++) {
			c.accept(buf.getAtIndex(LAYOUT, i));
		}
	}
}
