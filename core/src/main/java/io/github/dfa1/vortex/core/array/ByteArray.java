package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.function.LongBinaryOperator;

/// Concrete [Array] for I8/U8 primitive columns.
public final class ByteArray implements Array {

	private final DType dtype;
	private final long length;
	private final MemorySegment buffer;
	private final ArrayStats stats;

	public ByteArray(DType dtype, long length, MemorySegment buffer, ArrayStats stats) {
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

	public byte getByte(long i) {
		return buffer.get(ValueLayout.JAVA_BYTE, i);
	}

	public int getInt(long i) {
		byte raw = buffer.get(ValueLayout.JAVA_BYTE, i);
		boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U8;
		return unsigned ? Byte.toUnsignedInt(raw) : raw;
	}

	public long fold(long identity, LongBinaryOperator op) {
		MemorySegment buf = buffer;
		long n = length;
		long result = identity;
		for (long i = 0; i < n; i++) {
			result = op.applyAsLong(result, buf.get(ValueLayout.JAVA_BYTE, i));
		}
		return result;
	}
}
