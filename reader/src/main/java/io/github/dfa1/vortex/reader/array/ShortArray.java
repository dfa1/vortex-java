package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.util.function.LongBinaryOperator;

/// [Array] for I16/U16 primitive columns.
public non-sealed interface ShortArray extends Array {

    /// Returns the raw signed short value at the given index.
    ///
    /// @param i zero-based index (must be in `[0, length())`)
    /// @return the signed short value at position `i`
    short getShort(long i);

    /// Returns the element at the given index as an `int`, widening to
    /// unsigned if the dtype is U16.
    ///
    /// @param i zero-based index (must be in `[0, length())`)
    /// @return the value at position `i` as int
    default int getInt(long i) {
        short raw = getShort(i);
        if (dtype() instanceof DType.Primitive p && p.ptype() == PType.U16) {
            return Short.toUnsignedInt(raw);
        }
        return raw;
    }

    /// Folds all elements using the given binary operator and identity value.
    ///
    /// @param identity initial accumulator
    /// @param op       binary operator
    /// @return the final accumulated result
    long fold(long identity, LongBinaryOperator op);

    /// Passes each `short` element to the given consumer in order.
    ///
    /// @param c consumer that receives each short element
    default void forEachShort(ShortConsumer c) {
        long n = length();
        for (long i = 0; i < n; i++) {
            c.accept(getShort(i));
        }
    }
}
