package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;

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


    /// Reads cell `i` as a [BigDecimal] with the parent dtype's scale.
    ///
    /// @param i row index, `0 <= i < length()`
    /// @return decoded `BigDecimal`
    /// @throws VortexException             if the dtype isn't a [DType.Decimal]
    /// @throws IndexOutOfBoundsException if `i` is outside `[0, length())`
    public BigDecimal getDecimal(long i) {
        Objects.checkIndex(i, length);
        if (!(dtype instanceof DType.Decimal d)) {
            throw new VortexException("LazyDecimalArray: non-decimal dtype " + dtype);
        }
        return new BigDecimal(DecimalMantissas.readSignedLe(buf, i * byteWidth, byteWidth), d.scale());
    }

    /// Reads widths up to 8 bytes straight from the buffer, without a [java.math.BigInteger].
    ///
    /// @param i row index, `0 <= i < length()`
    /// @return the unscaled value at `i`
    /// @throws ArithmeticException if a 16- or 32-byte value does not fit a `long`
    @Override
    public long getUnscaledLong(long i) {
        Objects.checkIndex(i, length);
        long offset = i * byteWidth;
        return switch (byteWidth) {
            case 1 -> buf.get(ValueLayout.JAVA_BYTE, offset);
            case 2 -> buf.get(LE_SHORT, offset);
            case 4 -> buf.get(LE_INT, offset);
            case 8 -> buf.get(LE_LONG, offset);
            default -> DecimalMantissas.readSignedLe(buf, offset, byteWidth).longValueExact();
        };
    }

    // i128 and i256 (precision > 38): reverse the little-endian bytes into BigInteger's big-endian

    @Override
    public Array limited(long rows) {
        return new LazyDecimalArray(dtype, rows, buf.asSlice(0, rows * byteWidth), byteWidth);
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

    /// Returns the backing buffer directly — already materialized, no allocation.
    ///
    /// @return the backing little-endian two's-complement segment
    @Override
    public Optional<MemorySegment> segmentIfPresent() {
        return Optional.of(buf);
    }
}
