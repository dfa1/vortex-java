package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

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
    default int getInt(long i) {
        byte raw = getByte(i);
        if (dtype() instanceof DType.Primitive p && p.ptype() == PType.U8) {
            return Byte.toUnsignedInt(raw);
        }
        return raw;
    }

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

    /// Zero-copy truncation: a length-capping view over this array.
    /// [ChunkedByteArray] overrides to keep batch iteration.
    ///
    /// @param rows number of leading elements to keep
    /// @return a length-`rows` byte array view
    @Override
    default Array truncate(long rows) {
        return new OffsetByteArray(dtype(), rows, this, 0);
    }
}
