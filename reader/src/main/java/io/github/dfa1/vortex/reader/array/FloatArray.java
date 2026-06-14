package io.github.dfa1.vortex.reader.array;


import java.util.function.DoubleBinaryOperator;

/// [Array] for F32 primitive columns.
///
/// The default impl is [MaterializedFloatArray], a buffer-backed record
/// returned when an encoding decoder either materialises values eagerly or
/// has no lazy variant of its own.
public non-sealed interface FloatArray extends Array {

    /// Returns the float value at the given index.
    ///
    /// @param i zero-based index (must be in {@code [0, length())})
    /// @return the float value at position {@code i}
    float getFloat(long i);

    /// Folds all elements using the given binary operator and identity value.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to the accumulator and each float element (widened to double)
    /// @return the final accumulated result
    default double fold(double identity, DoubleBinaryOperator op) {
        long n = length();
        double result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsDouble(result, getFloat(i));
        }
        return result;
    }
}
