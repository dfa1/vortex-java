package io.github.dfa1.vortex.reader.array;

import java.math.BigDecimal;

/// [Array] for [io.github.dfa1.vortex.core.DType.Decimal] columns.
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
}
