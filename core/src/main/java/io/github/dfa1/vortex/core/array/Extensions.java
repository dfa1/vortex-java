package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.TimeUnit;

import java.time.LocalDate;
import java.time.LocalTime;

/// Decoding helpers for Vortex extension dtypes that ship as a primitive
/// storage array plus an extension id on the {@link DType}. Currently covers
/// {@code vortex.date}; {@code vortex.time} / {@code vortex.timestamp} live
/// on the TODO list until a public ScalarUnit type is available.
///
/// Lives in core so any reader-jar consumer can decode these cells without
/// reimplementing the storage conventions.
public final class Extensions {

    /// Extension id for date columns - storage is days since the Unix epoch
    /// (1970-01-01), Arrow-compatible.
    public static final String DATE = "vortex.date";

    /// Extension id for UUID columns - storage is
    /// {@code FixedSizeList(Primitive(U8), 16)}, Arrow-compatible.
    public static final String UUID_ID = "vortex.uuid";

    /// Extension id for time-of-day columns - storage is a signed integer
    /// counting seconds / milliseconds (I32) or microseconds / nanoseconds
    /// (I64) since midnight; the unit is the first byte of {@code ext.metadata()}.
    public static final String TIME = "vortex.time";

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
        checkBounds(i, array.length());
        return LocalDate.ofEpochDay(epochDay(array, i));
    }

    /// Decodes a {@code vortex.date} cell when the storage array no longer
    /// carries the Extension dtype — the case after {@code vortex.ext}'s
    /// decoder unwraps the storage child and returns it with its primitive
    /// dtype. Caller must supply the original {@link DType.Extension} so the
    /// extension id is still verified.
    ///
    /// @param ext     the column's declared extension dtype; must be {@code vortex.date}
    /// @param storage signed-integer storage array
    /// @param i       row index, {@code 0 <= i < storage.length()}
    /// @return decoded date
    /// @throws VortexException if {@code ext} isn't {@code vortex.date} or
    ///         {@code storage} isn't an integer primitive
    public static LocalDate localDate(DType.Extension ext, Array storage, long i) {
        if (!DATE.equals(ext.extensionId())) {
            throw new VortexException("localDate called with non-date extension: " + ext.extensionId());
        }
        checkBounds(i, storage.length());
        return LocalDate.ofEpochDay(epochDay(storage, i));
    }

    /// Decodes a {@code vortex.uuid} cell.
    ///
    /// Storage shape per Arrow's canonical UUID extension: a
    /// {@link FixedSizeListArray} of {@link ByteArray} (U8) with
    /// {@code fixedSize == 16}; row {@code i} is the 16 contiguous bytes
    /// {@code [i*16, i*16+16)} interpreted as a big-endian UUID.
    ///
    /// @param storage UUID extension's storage array
    /// @param i       row index, {@code 0 <= i < storage.length()}
    /// @return decoded {@link java.util.UUID}
    /// @throws VortexException if {@code storage} isn't a
    ///         {@code FixedSizeListArray<ByteArray>} of size 16
    public static java.util.UUID uuid(Array storage, long i) {
        checkBounds(i, storage.length());
        if (!(storage instanceof FixedSizeListArray fsl)) {
            throw new VortexException("uuid: expected FixedSizeListArray storage, got "
                    + storage.getClass().getSimpleName());
        }
        if (fsl.fixedSize() != 16) {
            throw new VortexException("uuid: expected fixedSize 16, got " + fsl.fixedSize());
        }
        if (!(fsl.elements() instanceof ByteArray bytes)) {
            throw new VortexException("uuid: expected ByteArray elements, got "
                    + fsl.elements().getClass().getSimpleName());
        }
        long base = i * 16;
        long msb = 0L;
        long lsb = 0L;
        for (int k = 0; k < 8; k++) {
            msb = (msb << 8) | (bytes.getByte(base + k) & 0xffL);
        }
        for (int k = 0; k < 8; k++) {
            lsb = (lsb << 8) | (bytes.getByte(base + 8 + k) & 0xffL);
        }
        return new java.util.UUID(msb, lsb);
    }

    /// Decodes a {@code vortex.time} cell to a {@link LocalTime}.
    ///
    /// The storage array must be a signed integer primitive ({@link IntArray}
    /// for second / millisecond precision, {@link LongArray} for microsecond /
    /// nanosecond precision), optionally wrapped in {@link MaskedArray}. The
    /// {@link TimeUnit} read from {@code ext.metadata()} byte 0 selects the
    /// precision; {@link TimeUnit#Days} is not valid for time-of-day and
    /// throws.
    ///
    /// @param ext     the column's declared extension dtype; must be {@code vortex.time}
    /// @param storage signed-integer storage array
    /// @param i       row index, {@code 0 <= i < storage.length()}
    /// @return decoded time-of-day
    /// @throws VortexException if {@code ext} isn't {@code vortex.time},
    ///         the metadata unit is {@link TimeUnit#Days}, or {@code storage}
    ///         isn't an integer primitive
    public static LocalTime localTime(DType.Extension ext, Array storage, long i) {
        if (!TIME.equals(ext.extensionId())) {
            throw new VortexException("localTime called with non-time extension: " + ext.extensionId());
        }
        checkBounds(i, storage.length());
        TimeUnit unit = readUnit(ext);
        if (unit == TimeUnit.Days) {
            throw new VortexException("localTime: Days unit not valid for vortex.time");
        }
        long raw = epochDay(storage, i);
        // raw is in `unit`; scale to nanos-of-day. divisor() = units per second.
        long nanos = raw * (1_000_000_000L / unit.divisor());
        return LocalTime.ofNanoOfDay(nanos);
    }

    private static TimeUnit readUnit(DType.Extension ext) {
        java.nio.ByteBuffer meta = ext.metadata();
        if (meta == null || !meta.hasRemaining()) {
            throw new VortexException("missing TimeUnit metadata byte for " + ext.extensionId());
        }
        return TimeUnit.fromTag(meta.get(meta.position()));
    }

    /// Same as {@link #uuid(Array, long)} but verifies the declared extension id.
    /// Use after {@code vortex.ext}'s decoder has unwrapped the storage and the
    /// Array no longer carries the Extension dtype.
    ///
    /// @param ext     the column's declared extension dtype; must be {@code vortex.uuid}
    /// @param storage UUID storage array
    /// @param i       row index, {@code 0 <= i < storage.length()}
    /// @return decoded {@link java.util.UUID}
    /// @throws VortexException if {@code ext} isn't {@code vortex.uuid} or storage shape doesn't match
    public static java.util.UUID uuid(DType.Extension ext, Array storage, long i) {
        if (!UUID_ID.equals(ext.extensionId())) {
            throw new VortexException("uuid called with non-uuid extension: " + ext.extensionId());
        }
        return uuid(storage, i);
    }

    private static void checkBounds(long i, long length) {
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("index " + i + " out of bounds for length " + length);
        }
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
