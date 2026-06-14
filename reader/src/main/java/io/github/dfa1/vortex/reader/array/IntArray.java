package io.github.dfa1.vortex.reader.array;


import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// [Array] for I32/U32 primitive columns.
///
/// The default impl is [MaterializedIntArray], a buffer-backed record
/// returned when an encoding decoder either materialises values eagerly or
/// has no lazy variant of its own.
public non-sealed interface IntArray extends Array {

    /// Returns the int value at the given index.
    ///
    /// @param i zero-based index (must be in {@code [0, length())})
    /// @return the int value at position {@code i}
    int getInt(long i);

    /// Passes each element to the given consumer in order.
    ///
    /// @param c consumer that receives each int element
    void forEachInt(IntConsumer c);

    /// Folds all elements using the given binary operator and identity value.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to the accumulator and each int element
    /// @return the final accumulated result
    int fold(int identity, IntBinaryOperator op);
}
