package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/// Metadata-only decimal array for `vortex.constant` columns.
///
/// Decodes the single scalar value once at construction time and returns it
/// for every row — no buffer allocated, no per-row BigDecimal reconstruction.
///
/// @param dtype     logical [DType.Decimal] type
/// @param length    total logical row count
/// @param value     decoded constant value
/// @param byteWidth element width in bytes (1/2/4/8/16); preserved for
///                  [#materialize(SegmentAllocator)]
public record LazyConstantDecimalArray(DType dtype, long length, BigDecimal value, int byteWidth) implements DecimalArray {

    /// Returns the constant decimal value for any valid row index.
    ///
    /// @param i row index, `0 <= i < length`
    /// @return the constant [java.math.BigDecimal] value
    public BigDecimal getDecimal(long i) {
        Objects.checkIndex(i, length);
        return value;
    }

    @Override
    public Array limited(long rows) {
        return new LazyConstantDecimalArray(dtype, rows, value, byteWidth);
    }

    /// Materialises by writing the single constant value, in little-endian
    /// two's-complement, `length` times into a fresh `byteWidth`-per-row segment.
    ///
    /// @param arena allocator for the output segment
    /// @return a read-only little-endian two's-complement segment of `length` rows
    /// @throws VortexException if `byteWidth` is not 1, 2, 4, or 8
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n * byteWidth);
        BigInteger unscaled = value.unscaledValue();
        long rawBits = unscaled.longValueExact();
        for (long i = 0; i < n; i++) {
            long off = i * byteWidth;
            switch (byteWidth) {
                case 1 -> dst.set(ValueLayout.JAVA_BYTE, off, (byte) rawBits);
                case 2 -> dst.set(PTypeIO.LE_SHORT, off, (short) rawBits);
                case 4 -> dst.set(PTypeIO.LE_INT, off, (int) rawBits);
                case 8 -> dst.set(PTypeIO.LE_LONG, off, rawBits);
                default -> throw new VortexException("LazyConstantDecimalArray: unsupported byteWidth " + byteWidth);
            }
        }
        return dst.asReadOnly();
    }
}
