package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.function.IntConsumer;

/// Concrete [Array] for variable-length binary / UTF-8 string columns.
///
/// Holds a bytes segment and an offsets array; element {@code i} occupies
/// {@code bytes[offsets[i]..offsets[i+1]]}. Provides zero-allocation
/// {@link #getByteLength} and {@link #forEachByteLength} for callers that only
/// need lengths, and allocating {@link #getBytes} for full value retrieval.
///
/// Stays backward-compatible via {@code buffer(0)} (→ bytes) and
/// {@code child(0)} (→ offsets Array) so codec internals that haven't
/// been updated continue to work.
public final class VarBinArray implements Array {

	private static final ValueLayout.OfInt  LE_INT  = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private final DType dtype;
	private final long length;
	private final MemorySegment bytes;
	private final Array offsetsArr;
	private final MemorySegment offsetsSeg;
	private final PType offsetsPtype;
	private final ArrayStats stats;

	public VarBinArray(DType dtype, long length, MemorySegment bytes,
	                   Array offsetsArr, PType offsetsPtype, ArrayStats stats) {
		this.dtype = dtype;
		this.length = length;
		this.bytes = bytes;
		this.offsetsArr = offsetsArr;
		this.offsetsSeg = offsetsArr.buffer(0);
		this.offsetsPtype = offsetsPtype;
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
		return bytes;
	}

	@Override
	public Array child(int i) {
		if (i != 0) {
			throw new IndexOutOfBoundsException(i);
		}
		return offsetsArr;
	}

	public byte[] getBytes(long i) {
		long start = readOffset(i);
		long end   = readOffset(i + 1);
		byte[] out = new byte[(int) (end - start)];
		MemorySegment.copy(bytes, start, MemorySegment.ofArray(out), 0, end - start);
		return out;
	}

	public int getByteLength(long i) {
		return (int) (readOffset(i + 1) - readOffset(i));
	}

	public void forEachByteLength(IntConsumer c) {
		MemorySegment seg = offsetsSeg;
		long n = length;
		if (offsetsPtype == PType.I32 || offsetsPtype == PType.U32) {
			for (long i = 0; i < n; i++) {
				c.accept(seg.getAtIndex(LE_INT, i + 1) - seg.getAtIndex(LE_INT, i));
			}
		} else {
			for (long i = 0; i < n; i++) {
				c.accept((int) (seg.getAtIndex(LE_LONG, i + 1) - seg.getAtIndex(LE_LONG, i)));
			}
		}
	}

	private long readOffset(long i) {
		if (offsetsPtype == PType.I32 || offsetsPtype == PType.U32) {
			return offsetsSeg.getAtIndex(LE_INT, i);
		}
		return offsetsSeg.getAtIndex(LE_LONG, i);
	}
}
