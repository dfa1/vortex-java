package io.github.dfa1.vortex.reader.array;


/// [Array] for bit-packed boolean columns (LSB-first, one byte per 8 elements).
///
/// The default impl is [MaterializedBoolArray], a buffer-backed record
/// returned when an encoding decoder either materialises values eagerly or
/// has no lazy variant of its own.
public non-sealed interface BoolArray extends Array {

    /// Returns the boolean value at the given logical index.
    ///
    /// @param i zero-based logical index (must be in `[0, length())`)
    /// @return the boolean value at position `i`
    boolean getBoolean(long i);

    /// Passes each boolean element to the given consumer in order.
    ///
    /// @param c consumer that receives each boolean element
    default void forEachBoolean(BooleanConsumer c) {
        long n = length();
        for (long i = 0; i < n; i++) {
            c.accept(getBoolean(i));
        }
    }
}
