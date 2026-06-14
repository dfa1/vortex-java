package io.github.dfa1.vortex.reader.array;


/// [Array] for F16 (IEEE 754 half-precision) columns.
///
/// Wire format: little-endian shorts (2 bytes/element). Element access widens
/// to `float` via {@link Float#float16ToFloat}. The default impl is
/// [MaterializedFloat16Array], a buffer-backed record returned when an
/// encoding decoder either materialises values eagerly or has no lazy
/// variant of its own.
public non-sealed interface Float16Array extends Array {

    /// Returns the element at the given index widened to a single-precision float.
    ///
    /// @param i zero-based index (must be in {@code [0, length())})
    /// @return the half-precision value at position {@code i} converted to {@code float}
    float getFloat(long i);
}
