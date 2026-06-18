package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.math.BigDecimal;

/// Metadata-only decimal array for `vortex.constant` columns.
///
/// Decodes the single scalar value once at construction time and returns it
/// for every row — no buffer allocated, no per-row BigDecimal reconstruction.
///
/// @param dtype     logical [DType.Decimal] type
/// @param length    total logical row count
/// @param value     decoded constant value
/// @param byteWidth element width in bytes (1/2/4/8/16); preserved for materialisation via
///                  [io.github.dfa1.vortex.reader.array.ArraySegments]
public record LazyConstantDecimalArray(DType dtype, long length, BigDecimal value, int byteWidth) implements DecimalArray {

    /// Returns the constant decimal value for any valid row index.
    ///
    /// @param i row index, `0 <= i < length`
    /// @return the constant [java.math.BigDecimal] value
    public BigDecimal getDecimal(long i) {
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("index " + i + " out of bounds for length " + length);
        }
        return value;
    }
}
