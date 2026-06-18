package io.github.dfa1.vortex.reader.array;


import java.util.function.LongBinaryOperator;

/// [Array] for I8/U8 primitive columns.
public non-sealed interface ByteArray extends Array {

    /// Returns the raw byte value at the given logical index.
    ///
    /// @param i zero-based logical index (must be in `[0, length())`)
    /// @return the raw signed byte value at position `i`
    byte getByte(long i);

    /// Returns the int value at the given logical index, applying unsigned
    /// widening for U8 columns.
    ///
    /// @param i zero-based logical index (must be in `[0, length())`)
    /// @return the value at position `i` as a signed int (U8 zero-extended)
    int getInt(long i);

    /// Reduces all elements to a single long using the supplied operator.
    ///
    /// @param identity initial accumulator value
    /// @param op       binary operator applied to accumulator and each element in order
    /// @return the final accumulated value
    long fold(long identity, LongBinaryOperator op);

    /// Passes each `byte` element to the given consumer in order.
    ///
    /// @param c consumer that receives each byte element
    default void forEachByte(ByteConsumer c) {
        long n = length();
        for (long i = 0; i < n; i++) {
            c.accept(getByte(i));
        }
    }
}
