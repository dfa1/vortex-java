package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// {@code vortex.date} — days since the Unix epoch, signed integer storage.
/// Per Arrow's canonical Date type.
public final class DateExtension implements Extension {

    /// Singleton instance.
    public static final DateExtension INSTANCE = new DateExtension();

    /// Public no-arg constructor for {@link java.util.ServiceLoader}.
    /// Prefer the {@link #INSTANCE} singleton in application code.
    public DateExtension() {
    }

    @Override
    public ExtensionId extensionId() {
        return ExtensionId.VORTEX_DATE;
    }

    @Override
    public DType.Extension dtype(boolean nullable) {
        return new DType.Extension(
                ExtensionId.VORTEX_DATE.id(),
                new DType.Primitive(PType.I32, nullable),
                null,
                nullable);
    }

    /// Decodes the date cell at row {@code i}.
    ///
    /// @param storage signed-integer storage (Byte/Short/Int/Long, possibly Masked)
    /// @param i       row index, {@code 0 <= i < storage.length()}
    /// @return decoded date
    /// @throws io.github.dfa1.vortex.core.VortexException if storage isn't an integer primitive
    public LocalDate decode(Array storage, long i) {
        ExtensionStorage.checkBounds(i, storage.length());
        return LocalDate.ofEpochDay(ExtensionStorage.epochInteger(storage, i));
    }

    /// Decodes every row of {@code storage} into a list of dates.
    ///
    /// @param storage signed-integer storage array
    /// @return list of decoded dates in row order
    public List<LocalDate> decodeAll(Array storage) {
        int n = Math.toIntExact(storage.length());
        List<LocalDate> out = new ArrayList<>(n);
        for (long i = 0; i < n; i++) {
            out.add(decode(storage, i));
        }
        return out;
    }

    /// Encodes a date as its epoch-day count for I32 storage.
    ///
    /// @param value local date
    /// @return days since the Unix epoch
    /// @throws ArithmeticException if {@code value} is too far from the epoch to fit in I32
    public int encode(LocalDate value) {
        return Math.toIntExact(value.toEpochDay());
    }

    /// Encodes a collection of dates into the storage layout the writer accepts
    /// for an I32 column (a primitive {@code int[]} in row order).
    ///
    /// @param values dates to encode
    /// @return packed {@code int[]} suitable for {@code writer.writeChunk}
    /// @throws ArithmeticException if any date is too far from the epoch to fit in I32
    public int[] encodeAll(Collection<LocalDate> values) {
        int[] out = new int[values.size()];
        int i = 0;
        for (LocalDate v : values) {
            out[i++] = encode(v);
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object encodeAll(DType.Extension dtype, Collection<?> values) {
        return encodeAll((Collection<LocalDate>) values);
    }
}
