package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/// Fallback [Array] holding the legacy decode shape: an arbitrary buffer set and
/// child array set with element access dispatched by `dtype` per call. Codecs
/// that have not yet been migrated to a specialised concrete subtype continue to
/// return this.
///
/// Per-call dtype switch makes typed accessors slower than the specialised
/// subtypes; migrate hot codecs first.
public final class GenericArray implements Array {

	private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfFloat LE_FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfDouble LE_DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private final DType dtype;
	private final long length;
	private final MemorySegment[] buffers;
	private final Array[] children;
	private final ArrayStats stats;

	public GenericArray(DType dtype, long length, MemorySegment[] buffers, Array[] children, ArrayStats stats) {
		this.dtype = dtype;
		this.length = length;
		this.buffers = buffers;
		this.children = children;
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
	public MemorySegment buffer(int i) {
		return buffers[i];
	}

	@Override
	public Array child(int i) {
		return children[i];
	}

	@Override
	public ArrayStats stats() {
		return stats;
	}

	public MemorySegment[] buffers() {
		return buffers;
	}

	public Array[] children() {
		return children;
	}

	@Override
	public long getLong(long i) {
		PType p = ((DType.Primitive) dtype).ptype();
		MemorySegment buf = buffers[0];
		return switch (p) {
			case I8 -> buf.get(ValueLayout.JAVA_BYTE, i);
			case U8 -> Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, i));
			case I16 -> buf.get(LE_SHORT, i * 2);
			case U16 -> Short.toUnsignedLong(buf.get(LE_SHORT, i * 2));
			case I32 -> buf.get(LE_INT, i * 4);
			case U32 -> Integer.toUnsignedLong(buf.get(LE_INT, i * 4));
			case I64, U64 -> buf.get(LE_LONG, i * 8);
			default -> throw new UnsupportedOperationException("getLong on " + p);
		};
	}

	@Override
	public int getInt(long i) {
		return (int) getLong(i);
	}

	@Override
	public double getDouble(long i) {
		PType p = ((DType.Primitive) dtype).ptype();
		MemorySegment buf = buffers[0];
		return switch (p) {
			case F32 -> buf.get(LE_FLOAT, i * 4);
			case F64 -> buf.get(LE_DOUBLE, i * 8);
			default -> throw new UnsupportedOperationException("getDouble on " + p);
		};
	}

	@Override
	public float getFloat(long i) {
		return (float) getDouble(i);
	}
}
