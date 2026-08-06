package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

/// Standard offset-based [VarBinArray].
///
/// Element `i` occupies `bytesSegment[offsetsSegment[i]..offsetsSegment[i+1]]`.
///
/// @param dtype          logical type (Utf8 or Binary)
/// @param length         number of variable-length elements
/// @param bytesSegment   concatenated raw byte data for all elements
/// @param offsetsSegment offsets segment of length `length + 1`
/// @param offsetsPtype   physical type of the offsets values (I32/U32 or I64/U64)
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record VarBinOffsetArray(DType dtype, long length, MemorySegment bytesSegment,
                                MemorySegment offsetsSegment, PType offsetsPtype)
        implements VarBinArray {

    @Override
    public byte[] getBytes(long i) {
        long start = readOffset(i);
        long end = readOffset(i + 1);
        int len = VarBinArrays.checkedLength(bytesSegment, start, end);
        byte[] out = new byte[len];
        MemorySegment.copy(bytesSegment, start, MemorySegment.ofArray(out), 0, len);
        return out;
    }

    @Override
    public String getString(long i) {
        return new String(getBytes(i), StandardCharsets.UTF_8);
    }

    @Override
    public int getByteLength(long i) {
        return VarBinArrays.checkedLength(bytesSegment, readOffset(i), readOffset(i + 1));
    }

    @Override
    public void forEachByteLength(IntConsumer c) {
        long n = length;
        // The loop reads offsets[0..n] at a fixed stride and must stay uniform to
        // vectorize (CLAUDE.md hot-loop rule), so the untrusted offsets segment is
        // sized once here rather than bounds-checked per row. A non-monotonic pair
        // still yields a negative length to the consumer; the typed accessors
        // ([#getBytes(long)], [#getByteLength(long)]) reject it when the row is read.
        checkOffsetsExtent(n);
        if (offsetsPtype == PType.I32 || offsetsPtype == PType.U32) {
            for (long i = 0; i < n; i++) {
                c.accept(offsetsSegment.getAtIndex(VortexFormat.LE_INT, i + 1)
                        - offsetsSegment.getAtIndex(VortexFormat.LE_INT, i));
            }
        } else {
            for (long i = 0; i < n; i++) {
                c.accept((int) (offsetsSegment.getAtIndex(VortexFormat.LE_LONG, i + 1)
                        - offsetsSegment.getAtIndex(VortexFormat.LE_LONG, i)));
            }
        }
    }

    @Override
    public VarBinArray limited(long rows) {
        if (rows >= length) {
            return this;
        }
        checkOffsetsExtent(rows);
        long byteEnd = readOffset(rows);
        if (byteEnd < 0 || byteEnd > bytesSegment.byteSize()) {
            throw new VortexException("varbin offset " + byteEnd + " at row " + rows
                    + " out of range for a data buffer of " + bytesSegment.byteSize() + " bytes");
        }
        int offBytes = offsetWidth();
        MemorySegment newOffsetsSeg = offsetsSegment.asSlice(0, (rows + 1) * offBytes);
        return new VarBinOffsetArray(dtype, rows,
                bytesSegment.asSlice(0, byteEnd), newOffsetsSeg, offsetsPtype);
    }

    /// Verifies the offsets segment holds the `rows + 1` offsets the array claims.
    ///
    /// @param rows number of rows whose offsets are about to be read
    private void checkOffsetsExtent(long rows) {
        int width = offsetWidth();
        if (rows + 1 > offsetsSegment.byteSize() / width) {
            throw new VortexException("varbin offsets segment of " + offsetsSegment.byteSize()
                    + " bytes holds fewer than " + (rows + 1) + " " + offsetsPtype + " offsets");
        }
    }

    private int offsetWidth() {
        return (offsetsPtype == PType.I32 || offsetsPtype == PType.U32) ? Integer.BYTES : Long.BYTES;
    }

    /// Reads offset `i` at the width of [#offsetsPtype].
    ///
    /// The offsets segment comes straight from an untrusted file, so an index past its
    /// end must surface as a [VortexException] rather than a raw
    /// `IndexOutOfBoundsException` (ADR 0003).
    ///
    /// @param i zero-based offset index, in `[0, length]`
    /// @return the offset value widened to a signed long
    private long readOffset(long i) {
        try {
            if (offsetsPtype == PType.I32 || offsetsPtype == PType.U32) {
                return offsetsSegment.getAtIndex(VortexFormat.LE_INT, i);
            }
            return offsetsSegment.getAtIndex(VortexFormat.LE_LONG, i);
        } catch (IndexOutOfBoundsException e) {
            throw new VortexException("varbin offset index " + i + " (" + offsetsPtype
                    + ") out of range for an offsets segment of "
                    + offsetsSegment.byteSize() + " bytes", e);
        }
    }
}
