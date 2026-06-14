package io.github.dfa1.vortex.reader.array;


import java.util.function.LongBinaryOperator;

/// [Array] for I16/U16 primitive columns.
///
/// The default impl is [MaterializedShortArray], a buffer-backed record
/// returned when an encoding decoder either materialises values eagerly or
/// has no lazy variant of its own.
public non-sealed interface ShortArray extends Array {

    /// Returns the raw signed short value at the given index.
    ///
    /// @param i zero-based index (must be in {@code [0, length())})
    /// @return the signed short value at position {@code i}
    short getShort(long i);

    /// Returns the element at the given index as an {@code int}, widening to
    /// unsigned if the dtype is U16.
    ///
    /// @param i zero-based index (must be in {@code [0, length())})
    /// @return the value at position {@code i} as int
    int getInt(long i);

    /// Folds all elements using the given binary operator and identity value.
    ///
    /// @param identity initial accumulator
    /// @param op       binary operator
    /// @return the final accumulated result
    long fold(long identity, LongBinaryOperator op);
}
