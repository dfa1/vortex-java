package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;

import java.time.LocalDate;

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
}
