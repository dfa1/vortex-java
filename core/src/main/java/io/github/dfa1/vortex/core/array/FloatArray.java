package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.DoubleBinaryOperator;

/// Concrete [Array] for F32 primitive columns.
public final class FloatArray implements Array {

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

	public float getFloat(long i) {
		return buffer.getAtIndex(PTypeIO.LE_FLOAT, i);
	}

	public double fold(double identity, DoubleBinaryOperator op) {
		MemorySegment buf = buffer;
		long n = length;
		double result = identity;
		for (long i = 0; i < n; i++) {
			result = op.applyAsDouble(result, buf.getAtIndex(PTypeIO.LE_FLOAT, i));
		}
		return result;
	}
}
