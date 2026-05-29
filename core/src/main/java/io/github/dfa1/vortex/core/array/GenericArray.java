package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;

import java.lang.foreign.MemorySegment;

/// Fallback [Array] for dtypes that lack a dedicated concrete subtype.
///
/// Holds raw buffer segments and child arrays. Used by codecs during migration
/// and for less-common dtypes (e.g. Decimal, Ext) where typed accessors are
/// not yet implemented.
public final class GenericArray implements Array {

	private final DType dtype;
	private final long length;
	private final MemorySegment[] buffers;
	private final Array[] children;

	public GenericArray(DType dtype, long length, MemorySegment[] buffers, Array[] children) {
		this.dtype = dtype;
		this.length = length;
		this.buffers = buffers;
		this.children = children;
	}

	public GenericArray(DType dtype, long length, MemorySegment buffer) {
		this(dtype, length, new MemorySegment[]{buffer}, new Array[0]);
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
		return ArrayStats.empty();
	}

	@Override
	public MemorySegment buffer(int i) {
		return buffers[i];
	}

	@Override
	public Array child(int i) {
		return children[i];
	}
}
