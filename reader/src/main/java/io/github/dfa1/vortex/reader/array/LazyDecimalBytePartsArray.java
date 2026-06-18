package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.math.BigDecimal;

/// Lazy `vortex.decimal_byte_parts` reassembly.
///
/// With `lower_part_count == 0` (the only shape this codebase currently
/// emits or accepts) the encoding stores its mantissa as a single signed-integer
/// child column, paired with the parent's {@link DType.Decimal} precision and
/// scale. {@link #getDecimal(long)} reads one cell from the child via
/// {@link DecimalBytePartsArrays#readMantissa(Array, long)} and combines it with
/// the dtype scale to produce a {@link BigDecimal} on demand — no buffer
/// materialisation occurs at construction time.
///
/// @param dtype  the parent {@link DType.Decimal} dtype (precision + scale + nullable)
/// @param length total logical row count
/// @param msp    child array holding the most-significant integer part of the mantissa
public record LazyDecimalBytePartsArray(DType dtype, long length, Array msp) implements DecimalArray {

    /// Reads cell `i` as a {@link BigDecimal} with the parent dtype's scale.
    ///
    /// @param i row index, `0 <= i < length()`
    /// @return decoded `BigDecimal`
    /// @throws VortexException             if the dtype isn't a {@link DType.Decimal} or the
    ///                                     mantissa cell is null
    /// @throws IndexOutOfBoundsException if `i` is outside `[0, length())`
    public BigDecimal getDecimal(long i) {
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("index " + i + " out of bounds for length " + length);
        }
        if (!(dtype instanceof DType.Decimal d)) {
            throw new VortexException("LazyDecimalBytePartsArray: non-decimal dtype " + dtype);
        }
        return new BigDecimal(DecimalBytePartsArrays.readMantissa(msp, i), d.scale());
    }
}
