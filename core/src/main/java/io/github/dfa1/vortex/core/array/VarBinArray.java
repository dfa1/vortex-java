package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
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

    private final DType dtype;
    private final long length;
    private final MemorySegment bytes;
    private final Array offsetsArr;
    private final MemorySegment offsetsSeg;
    private final PType offsetsPtype;

    // dict mode: non-null when this array lazily resolves through a dictionary
    private final MemorySegment dictValOffsets;
    private final PType dictValOffPType;
    private final MemorySegment dictCodesSegs;
    private final PType dictCodesPType;

    /// Creates a new {@code VarBinArray} in offset mode.
    ///
    /// @param dtype        logical type (Utf8 or Binary)
    /// @param length       number of variable-length elements
    /// @param bytes        concatenated raw byte data for all elements
    /// @param offsetsArr   offsets array of length {@code length + 1}; {@code offsets[i]..offsets[i+1]} is element {@code i}
    /// @param offsetsPtype physical type of the offsets values (I32/U32 or I64/U64)
    public VarBinArray(DType dtype, long length, MemorySegment bytes, Array offsetsArr, PType offsetsPtype) {
        this.dtype = dtype;
        this.length = length;
        this.bytes = bytes;
        this.offsetsArr = offsetsArr;
        this.offsetsSeg = offsetsArr.buffer(0);
        this.offsetsPtype = offsetsPtype;
        this.dictValOffsets = null;
        this.dictValOffPType = null;
        this.dictCodesSegs = null;
        this.dictCodesPType = null;
    }

    private VarBinArray(DType dtype, long length,
            MemorySegment dictValBytes, MemorySegment dictValOffsets, PType dictValOffPType,
            MemorySegment dictCodesSegs, PType dictCodesPType) {
        this.dtype = dtype;
        this.length = length;
        this.bytes = dictValBytes;
        this.offsetsArr = null;
        this.offsetsSeg = null;
        this.offsetsPtype = null;
        this.dictValOffsets = dictValOffsets;
        this.dictValOffPType = dictValOffPType;
        this.dictCodesSegs = dictCodesSegs;
        this.dictCodesPType = dictCodesPType;
    }

    /// Creates a dict-mode VarBinArray. Lengths and bytes are resolved via the
    /// dictionary on each access; no string materialization occurs at construction time.
    ///
    /// @param dtype           logical type (Utf8 or Binary)
    /// @param n               number of logical elements (rows)
    /// @param dictValBytes    concatenated raw bytes for all dictionary values
    /// @param dictValOffsets  offsets into {@code dictValBytes} for each dictionary entry (length = dictSize + 1)
    /// @param dictValOffPType physical type of the dictionary value offsets
    /// @param dictCodesSegs   per-row dictionary code indices (length = n)
    /// @param dictCodesPType  physical type of the dictionary codes
    /// @return a new dict-mode {@code VarBinArray}
    public static VarBinArray ofDict(DType dtype, long n,
            MemorySegment dictValBytes,
            MemorySegment dictValOffsets, PType dictValOffPType,
            MemorySegment dictCodesSegs, PType dictCodesPType) {
        return new VarBinArray(dtype, n, dictValBytes, dictValOffsets, dictValOffPType,
                dictCodesSegs, dictCodesPType);
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

    /// Returns a copy of the raw bytes for element {@code i}.
    ///
    /// @param i zero-based logical index (must be in {@code [0, length)})
    /// @return a newly allocated byte array containing the raw bytes of element {@code i}
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

    /// Returns the UTF-8 decoded string for element {@code i}.
    ///
    /// @param i zero-based logical index (must be in {@code [0, length)})
    /// @return the UTF-8 string at position {@code i}
    public String getString(long i) {
        return new String(getBytes(i), StandardCharsets.UTF_8);
    }

    /// Returns the byte length of element {@code i} without copying the data.
    ///
    /// @param i zero-based logical index (must be in {@code [0, length)})
    /// @return the number of bytes in element {@code i}
    public int getByteLength(long i) {
        if (dictCodesSegs != null) {
            long code = dictReadCode(i);
            return (int) (dictReadOff(code + 1) - dictReadOff(code));
        }
        return (int) (readOffset(i + 1) - readOffset(i));
    }

    /// Passes the byte length of each element to the given consumer in row order.
    ///
    /// @param c consumer that receives the byte length of each element
    public void forEachByteLength(IntConsumer c) {
        if (dictCodesSegs != null) {
            for (long i = 0; i < length; i++) {
                long code = dictReadCode(i);
                c.accept((int) (dictReadOff(code + 1) - dictReadOff(code)));
            }
            return;
        }
        MemorySegment seg = offsetsSeg;
        long n = length;
        if (offsetsPtype == PType.I32 || offsetsPtype == PType.U32) {
            for (long i = 0; i < n; i++) {
                c.accept(seg.getAtIndex(PTypeIO.LE_INT, i + 1) - seg.getAtIndex(PTypeIO.LE_INT, i));
            }
        } else {
            for (long i = 0; i < n; i++) {
                c.accept((int) (seg.getAtIndex(PTypeIO.LE_LONG, i + 1) - seg.getAtIndex(PTypeIO.LE_LONG, i)));
            }
        }
    }

    private long readOffset(long i) {
        if (offsetsPtype == PType.I32 || offsetsPtype == PType.U32) {
            return offsetsSeg.getAtIndex(PTypeIO.LE_INT, i);
        }
        return offsetsSeg.getAtIndex(PTypeIO.LE_LONG, i);
    }

    private long dictReadCode(long i) {
        return switch (dictCodesPType) {
            case U8 -> Byte.toUnsignedLong(dictCodesSegs.get(ValueLayout.JAVA_BYTE, i));
            case U16 -> Short.toUnsignedLong(dictCodesSegs.getAtIndex(PTypeIO.LE_SHORT, i));
            case U32 -> Integer.toUnsignedLong(dictCodesSegs.getAtIndex(PTypeIO.LE_INT, i));
            case I32 -> dictCodesSegs.getAtIndex(PTypeIO.LE_INT, i);
            case I64, U64 -> dictCodesSegs.getAtIndex(PTypeIO.LE_LONG, i);
            default -> throw new VortexException("unsupported codes ptype: " + dictCodesPType);
        };
    }

    private long dictReadOff(long i) {
        if (dictValOffPType == PType.I32 || dictValOffPType == PType.U32) {
            return dictValOffsets.getAtIndex(PTypeIO.LE_INT, i);
        }
        return dictValOffsets.getAtIndex(PTypeIO.LE_LONG, i);
    }

    /// Returns a new VarBinArray containing only the first {@code rows} elements.
    ///
    /// @param rows number of rows to retain; if {@code rows >= length} returns this array unchanged
    /// @return a {@code VarBinArray} containing the first {@code rows} elements
    public VarBinArray truncate(long rows) {
        if (rows >= length) {
            return this;
        }
        if (dictCodesSegs != null) {
            int codeBytes = dictCodesPType.byteSize();
            return VarBinArray.ofDict(dtype, rows, bytes, dictValOffsets, dictValOffPType,
                    dictCodesSegs.asSlice(0, rows * codeBytes), dictCodesPType);
        }
        long byteEnd = readOffset(rows);
        int offBytes = (offsetsPtype == PType.I32 || offsetsPtype == PType.U32) ? Integer.BYTES : Long.BYTES;
        MemorySegment newOffsetsSeg = offsetsSeg.asSlice(0, (rows + 1) * offBytes);
        DType offDtype = new DType.Primitive(offsetsPtype, false);
        Array newOffsetsArr = (offBytes == Integer.BYTES)
                                      ? new IntArray(offDtype, rows + 1, newOffsetsSeg)
                                      : new LongArray(offDtype, rows + 1, newOffsetsSeg);
        return new VarBinArray(dtype, rows, bytes.asSlice(0, byteEnd > 0 ? byteEnd : 0), newOffsetsArr, offsetsPtype);
    }
}
