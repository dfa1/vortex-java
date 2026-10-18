package io.github.dfa1.vortex.reader.array;

import java.math.BigDecimal;

/// [Array] for [io.github.dfa1.vortex.core.model.DType.Decimal] columns.
///
/// Concrete subtypes include [LazyDecimalArray] (direct LE-byte buffer),
/// [LazyDecimalBytePartsArray] (byte-parts reassembly), and
/// [LazyConstantDecimalArray] (single constant value broadcast).
public non-sealed interface DecimalArray extends Array {

    /// Returns the decoded value at the given row index.
    ///
    /// @param i zero-based row index (must be in `[0, length())`)
    /// @return the [java.math.BigDecimal] value at position `i`
    BigDecimal getDecimal(long i);

    /// Returns the unscaled value at `i` as a `long`: the decimal is this times ten to the minus
    /// scale. Every value of a decimal with precision 18 or less fits, so a fold over such a column
    /// can total longs instead of building a [BigDecimal] per row.
    ///
    /// @param i zero-based row index (must be in `[0, length())`)
    /// @return the unscaled value at position `i`
    /// @throws ArithmeticException if the value does not fit a `long` (precision past 18)
    default long getUnscaledLong(long i) {
        return getDecimal(i).unscaledValue().longValueExact();
    }
}
