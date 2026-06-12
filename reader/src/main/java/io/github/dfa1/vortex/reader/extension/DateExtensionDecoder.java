package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.extension.ExtensionId;

import io.github.dfa1.vortex.reader.ExtensionDecoder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/// {@code vortex.date} — days since the Unix epoch, signed integer storage.
/// Per Arrow's canonical Date type.
public final class DateExtensionDecoder implements ExtensionDecoder {

    /// Singleton instance.
    public static final DateExtensionDecoder INSTANCE = new DateExtensionDecoder();

    /// Public no-arg constructor for {@link java.util.ServiceLoader}.
    /// Prefer the {@link #INSTANCE} singleton in application code.
    public DateExtensionDecoder() {
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

    /// Decodes every row of {@code storage} into a list of dates. {@link MaskedArray}
    /// storage yields {@code null} at invalid positions instead of throwing.
    ///
    /// @param storage signed-integer storage array (optionally wrapped in {@code MaskedArray})
    /// @return list of decoded dates in row order; {@code null} entries mark invalid rows
    public List<LocalDate> decodeAll(Array storage) {
        int n = Math.toIntExact(storage.length());
        List<LocalDate> out = new ArrayList<>(n);
        if (storage instanceof MaskedArray masked) {
            for (long i = 0; i < n; i++) {
                out.add(masked.isValid(i) ? decode(masked.inner(), i) : null);
            }
            return out;
        }
        for (long i = 0; i < n; i++) {
            out.add(decode(storage, i));
        }
        return out;
    }
}
