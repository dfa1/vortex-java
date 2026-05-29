package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;

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
/// {@code child(0)} (→ offsets Array) so encoding internals that haven't
/// been updated continue to work.
///
/// Dict mode: created via {@link #ofDict}. Stores dict values + codes directly;
/// all accessors resolve through the dictionary without materializing strings.
public final class VarBinArray implements Array {

	private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfInt   LE_INT   = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfLong  LE_LONG  = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private final DType dtype;
	private final long length;
	private final MemorySegment bytes;
	private final Array offsetsArr;
	private final MemorySegment offsetsSeg;
	private final PType offsetsPtype;
	private final ArrayStats stats;

	// dict mode: non-null when this array lazily resolves through a dictionary
	private final MemorySegment dictValOffsets;
	private final PType dictValOffPType;
	private final MemorySegment dictCodesSegs;
	private final PType dictCodesPType;

	public VarBinArray(DType dtype, long length, MemorySegment bytes,
	                   Array offsetsArr, PType offsetsPtype, ArrayStats stats) {
		this.dtype = dtype;
		this.length = length;
		this.bytes = bytes;
		this.offsetsArr = offsetsArr;
		this.offsetsSeg = offsetsArr.buffer(0);
		this.offsetsPtype = offsetsPtype;
		this.stats = stats;
		this.dictValOffsets = null;
		this.dictValOffPType = null;
		this.dictCodesSegs = null;
		this.dictCodesPType = null;
	}

	private VarBinArray(DType dtype, long length,
	                    MemorySegment dictValBytes, MemorySegment dictValOffsets, PType dictValOffPType,
	                    MemorySegment dictCodesSegs, PType dictCodesPType, ArrayStats stats) {
		this.dtype = dtype;
		this.length = length;
		this.bytes = dictValBytes;
		this.offsetsArr = null;
		this.offsetsSeg = null;
		this.offsetsPtype = null;
		this.stats = stats;
		this.dictValOffsets = dictValOffsets;
		this.dictValOffPType = dictValOffPType;
		this.dictCodesSegs = dictCodesSegs;
		this.dictCodesPType = dictCodesPType;
	}

	/// Creates a dict-mode VarBinArray. Lengths and bytes are resolved via the
	/// dictionary on each access; no string materialization occurs at construction time.
	public static VarBinArray ofDict(DType dtype, long n,
	                                 MemorySegment dictValBytes,
	                                 MemorySegment dictValOffsets, PType dictValOffPType,
	                                 MemorySegment dictCodesSegs, PType dictCodesPType,
	                                 ArrayStats stats) {
		return new VarBinArray(dtype, n, dictValBytes, dictValOffsets, dictValOffPType,
				dictCodesSegs, dictCodesPType, stats);
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
		if (offsetsArr == null) {
			throw new IllegalStateException("child(0) not available in dict mode");
		}
		return offsetsArr;
	}

	public byte[] getBytes(long i) {
		if (dictCodesSegs != null) {
			long code = dictReadCode(i);
			long start = dictReadOff(code);
			long end = dictReadOff(code + 1);
			byte[] out = new byte[(int) (end - start)];
			MemorySegment.copy(bytes, start, MemorySegment.ofArray(out), 0, end - start);
			return out;
		}
		long start = readOffset(i);
		long end = readOffset(i + 1);
		byte[] out = new byte[(int) (end - start)];
		MemorySegment.copy(bytes, start, MemorySegment.ofArray(out), 0, end - start);
		return out;
	}

	public int getByteLength(long i) {
		if (dictCodesSegs != null) {
			long code = dictReadCode(i);
			return (int) (dictReadOff(code + 1) - dictReadOff(code));
		}
		return (int) (readOffset(i + 1) - readOffset(i));
	}

	public void forEachByteLength(IntConsumer c) {
		if (dictCodesSegs != null) {
			long n = length;
			for (long i = 0; i < n; i++) {
				long code = dictReadCode(i);
				c.accept((int) (dictReadOff(code + 1) - dictReadOff(code)));
			}
			return;
		}
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

	private long dictReadCode(long i) {
		return switch (dictCodesPType) {
			case U8  -> Byte.toUnsignedLong(dictCodesSegs.get(ValueLayout.JAVA_BYTE, i));
			case U16 -> Short.toUnsignedLong(dictCodesSegs.getAtIndex(LE_SHORT, i));
			case U32 -> Integer.toUnsignedLong(dictCodesSegs.getAtIndex(LE_INT, i));
			case I32 -> dictCodesSegs.getAtIndex(LE_INT, i);
			case I64, U64 -> dictCodesSegs.getAtIndex(LE_LONG, i);
			default  -> throw new VortexException("unsupported codes ptype: " + dictCodesPType);
		};
	}

	private long dictReadOff(long i) {
		if (dictValOffPType == PType.I32 || dictValOffPType == PType.U32) {
			return dictValOffsets.getAtIndex(LE_INT, i);
		}
		return dictValOffsets.getAtIndex(LE_LONG, i);
	}
}
