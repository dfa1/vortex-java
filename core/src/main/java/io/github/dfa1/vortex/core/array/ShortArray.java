package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/// Concrete [Array] for I16/U16 primitive columns.
public final class ShortArray implements Array {

	private static final ValueLayout.OfShort LAYOUT =
			ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private final DType dtype;
	private final long length;
	private final MemorySegment buffer;
	private final ArrayStats stats;

	public ShortArray(DType dtype, long length, MemorySegment buffer, ArrayStats stats) {
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

	public short getShort(long i) {
		return buffer.getAtIndex(LAYOUT, i);
	}

	public int getInt(long i) {
		short raw = buffer.getAtIndex(LAYOUT, i);
		boolean unsigned = dtype instanceof DType.Primitive p && p.ptype() == PType.U16;
		return unsigned ? Short.toUnsignedInt(raw) : raw;
	}
}
