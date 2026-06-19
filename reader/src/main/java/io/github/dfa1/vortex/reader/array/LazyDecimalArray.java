package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteOrder;

/// Lazy `vortex.decimal` array.
///
/// `vortex.decimal` stores one little-endian two's-complement integer per row;
/// the integer's byte width (1/2/4/8/16) is fixed for the whole array and chosen
/// by the writer based on precision. The buffer is a mmap slice — decode wraps
/// it; per-row [#getDecimal(long)] reads `byteWidth` bytes and combines
/// them with the parent dtype's scale into a [BigDecimal] on demand. No
/// arena allocation happens at construction time.
///
/// @param dtype     the parent [DType.Decimal] dtype (precision + scale + nullable)
/// @param length    total logical row count
/// @param buf       backing little-endian two's-complement integer buffer
/// @param byteWidth element width in bytes; one of 1, 2, 4, 8, 16
public record LazyDecimalArray(DType dtype, long length, MemorySegment buf, int byteWidth) implements DecimalArray {

    private static final ValueLayout.OfShort SHORT_LE =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT_LE =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG_LE =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /// Reads cell `i` as a [BigDecimal] with the parent dtype's scale.
    ///
    /// @param i row index, `0 <= i < length()`
    /// @return decoded `BigDecimal`
    /// @throws VortexException             if the dtype isn't a [DType.Decimal]
    /// @throws IndexOutOfBoundsException if `i` is outside `[0, length())`
    public BigDecimal getDecimal(long i) {
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("index " + i + " out of bounds for length " + length);
        }
        if (!(dtype instanceof DType.Decimal d)) {
            throw new VortexException("LazyDecimalArray: non-decimal dtype " + dtype);
        }
        return new BigDecimal(readSignedLe(buf, i * byteWidth, byteWidth), d.scale());
    }

    private static BigInteger readSignedLe(MemorySegment buf, long offset, int width) {
        return switch (width) {
            case 1 -> BigInteger.valueOf(buf.get(ValueLayout.JAVA_BYTE, offset));
            case 2 -> BigInteger.valueOf(buf.get(SHORT_LE, offset));
            case 4 -> BigInteger.valueOf(buf.get(INT_LE, offset));
            case 8 -> BigInteger.valueOf(buf.get(LONG_LE, offset));
            case 16 -> readSigned128Le(buf, offset);
            default -> throw new VortexException("LazyDecimalArray: unsupported element width " + width);
        };
    }

    private static BigInteger readSigned128Le(MemorySegment buf, long offset) {
        byte[] be = new byte[16];
        for (int k = 0; k < 16; k++) {
            be[15 - k] = buf.get(ValueLayout.JAVA_BYTE, offset + k);
        }
        return new BigInteger(be);
    }

    @Override
    public Array limited(long rows) {
        return new LazyDecimalArray(dtype, rows, buf.asSlice(0, rows * (long) byteWidth), byteWidth);
    }

    /// Returns the backing buffer directly — already a contiguous little-endian
    /// two's-complement integer segment (`byteWidth` bytes per row), so no copy or
    /// allocation is needed.
    ///
    /// @param arena unused; the existing buffer is returned as-is
    /// @return the backing little-endian two's-complement segment
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        return buf;
    }
}
