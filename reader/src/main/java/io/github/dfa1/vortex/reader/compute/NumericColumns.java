package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyConstantBoolArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.ShortArray;

/// Value-access helpers for numeric primitive columns, shared by the compute kernels
/// ([FusedFilterAggregate], [DictFilter], [GroupedAggregate]).
///
/// These unwrap a [MaskedArray] into its payload and validity, classify a column's value domain
/// (long-domain integer, floating, numeric primitive), and read a long-domain element widened to a
/// `long` with the column's signedness applied — the boxing-free reads the kernels' hot loops hoist
/// out once before scanning.
final class NumericColumns {

    private NumericColumns() {
    }

    /// Unwraps a [MaskedArray] to its non-nullable payload, or returns the array unchanged.
    ///
    /// @param array the array to unwrap
    /// @return the underlying value array
    static Array unwrap(Array array) {
        return array instanceof MaskedArray masked ? masked.inner() : array;
    }

    /// Returns the validity bitmap of a [MaskedArray], an all-false one for a [NullArray] (an
    /// all-null chunk of a type with no masked form, such as a uuid or a list), or `null` when the
    /// array carries no nulls.
    ///
    /// @param array the array to inspect
    /// @return the validity bitmap, or `null`
    static BoolArray validityOf(Array array) {
        return switch (array) {
            case MaskedArray masked -> masked.validity();
            case NullArray nulls -> new LazyConstantBoolArray(DType.BOOL, nulls.length(), false);
            default -> null;
        };
    }

    /// Reports whether the unwrapped array is a specialized long-domain primitive.
    ///
    /// @param data the unwrapped array
    /// @return `true` for [LongArray] / [IntArray] / [ShortArray] / [ByteArray]
    static boolean isLongDomain(Array data) {
        return data instanceof LongArray || data instanceof IntArray
                || data instanceof ShortArray || data instanceof ByteArray;
    }

    /// Reads element `i` of a long-domain array widened to a `long`, zero-extending narrow unsigned
    /// columns exactly as [Values#valueAt(Array, long)] boxes them.
    ///
    /// @param data     the unwrapped long-domain array
    /// @param unsigned whether the column is unsigned
    /// @param i        the zero-based position
    /// @return the widened element value
    static long widenLong(Array data, boolean unsigned, long i) {
        if (data instanceof LongArray la) {
            return la.getLong(i);
        }
        if (data instanceof IntArray ia) {
            return unsigned ? (ia.getInt(i) & 0xFFFFFFFFL) : ia.getInt(i);
        }
        if (data instanceof ShortArray sa) {
            return unsigned ? (sa.getShort(i) & 0xFFFFL) : sa.getShort(i);
        }
        ByteArray ba = (ByteArray) data;
        return unsigned ? (ba.getByte(i) & 0xFFL) : ba.getByte(i);
    }
}
