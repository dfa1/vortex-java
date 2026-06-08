package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.time.LocalDate;

/// Decoding helpers for Vortex extension dtypes (e.g. {@code vortex.date},
/// {@code vortex.timestamp}) that ship as primitive storage arrays plus an
/// extension id on the {@link DType}.
///
/// Lives in core so any reader-jar consumer can decode these cells without
/// reimplementing the storage conventions.
public final class Extensions {

    /// Extension id for date columns - storage is days since the Unix epoch
    /// (1970-01-01), Arrow-compatible.
    public static final String DATE = "vortex.date";

    private Extensions() {
    }

    /// Decodes a {@code vortex.date} cell to a {@link LocalDate}.
    ///
    /// The storage array must be one of the integer primitive arrays
    /// ({@link ByteArray}, {@link ShortArray}, {@link IntArray}, {@link LongArray}),
    /// optionally wrapped in a {@link MaskedArray}. The cell value is read as a
    /// signed integer giving days since the Unix epoch.
    ///
    /// @param array array whose dtype is {@code ext<vortex.date>}
    /// @param i     row index, {@code 0 <= i < array.length()}
    /// @return decoded date
    /// @throws VortexException if {@code array}'s dtype isn't {@code ext<vortex.date>}
    ///         or its storage isn't an integer primitive
    public static LocalDate localDate(Array array, long i) {
        if (!(array.dtype() instanceof DType.Extension ext) || !DATE.equals(ext.extensionId())) {
            throw new VortexException("localDate called on non-date dtype: " + array.dtype());
        }
        return LocalDate.ofEpochDay(epochDay(array, i));
    }

    /// Decodes a {@code vortex.date} cell directly from its storage array.
    ///
    /// Use when the caller has already established (via column metadata) that
    /// the array represents a date, but the Array itself no longer carries the
    /// Extension dtype - the case after {@code vortex.ext}'s decoder unwraps
    /// the storage child and returns it with its primitive dtype.
    ///
    /// @param storage signed-integer storage array
    /// @param i       row index, {@code 0 <= i < storage.length()}
    /// @return decoded date
    /// @throws VortexException if {@code storage} isn't an integer primitive
    public static LocalDate localDateFromStorage(Array storage, long i) {
        return LocalDate.ofEpochDay(epochDay(storage, i));
    }

    private static long epochDay(Array array, long i) {
        return switch (array) {
            case ByteArray a -> a.getByte(i);
            case ShortArray a -> a.getShort(i);
            case IntArray a -> a.getInt(i);
            case LongArray a -> a.getLong(i);
            case MaskedArray a -> epochDay(a.inner(), i);
            default -> throw new VortexException(
                    "localDate: unsupported storage type " + array.getClass().getSimpleName());
        };
    }
}
