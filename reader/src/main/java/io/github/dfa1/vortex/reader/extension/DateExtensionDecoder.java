package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.core.model.ExtensionId;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/// `vortex.date` — days (`I32`) or milliseconds (`I64`) since the Unix epoch, per the time unit in
/// the metadata. Per Arrow's canonical `Date32`/`Date64` types.
public final class DateExtensionDecoder implements ExtensionDecoder<LocalDate> {

    /// Singleton instance.
    public static final DateExtensionDecoder INSTANCE = new DateExtensionDecoder();

    private static final long MILLIS_PER_DAY = 86_400_000L;

    @Override
    public ExtensionId extensionId() {
        return ExtensionId.VORTEX_DATE;
    }

    /// Applies Rust's `validate_dtype` rules for `vortex.date`.
    @Override
    public void validate(DType.Extension dtype) {
        ExtensionId.VORTEX_DATE.validate(dtype);
    }

    @Override
    public Class<LocalDate> domainType() {
        return LocalDate.class;
    }

    @Override
    public DType.Extension dtype(boolean nullable) {
        // Rust vortex.date metadata: 1 byte = TimeUnit tag (Days = 4), required by Rust reader.
        java.lang.foreign.MemorySegment meta = java.lang.foreign.MemorySegment.ofArray(new byte[]{(byte) TimeUnit.Days.ordinal()});
        return new DType.Extension(
                ExtensionId.VORTEX_DATE.id(),
                new DType.Primitive(PType.I32, nullable),
                meta,
                nullable);
    }

    /// Decodes the date cell at row `i`, in the column's time unit.
    ///
    /// @param dtype   declared extension dtype, carrying the time unit
    /// @param storage signed-integer storage (Byte/Short/Int/Long, possibly Masked)
    /// @param i       row index, `0 <= i < storage.length()`
    /// @return decoded date
    /// @throws io.github.dfa1.vortex.core.error.VortexException if storage isn't an integer primitive
    ///         or the unit is not Days or Milliseconds
    public LocalDate decode(DType.Extension dtype, Array storage, long i) {
        ExtensionStorage.checkBounds(i, storage.length());
        return date(ExtensionStorage.readUnit(dtype), ExtensionStorage.epochInteger(storage, i));
    }

    /// Converts one raw storage value to a date, in the column's time unit.
    ///
    /// @param dtype declared extension dtype, carrying the time unit
    /// @param raw   storage value: days, or milliseconds, since the Unix epoch
    /// @return the date
    /// @throws io.github.dfa1.vortex.core.error.VortexException if the unit is not Days or Milliseconds
    public LocalDate date(DType.Extension dtype, long raw) {
        return date(ExtensionStorage.readUnit(dtype), raw);
    }

    /// Decodes every row of `storage` into a list of dates. [MaskedArray]
    /// storage yields `null` at invalid positions instead of throwing.
    ///
    /// @param dtype   declared extension dtype, carrying the time unit
    /// @param storage signed-integer storage array (optionally wrapped in `MaskedArray`)
    /// @return list of decoded dates in row order; `null` entries mark invalid rows
    @Override
    public List<LocalDate> decodeAll(DType.Extension dtype, Array storage) {
        TimeUnit unit = ExtensionStorage.readUnit(dtype);
        int n = IoBounds.toIntSize(storage.length());
        List<LocalDate> out = new ArrayList<>(n);
        if (storage instanceof MaskedArray masked) {
            for (int i = 0; i < n; i++) {
                out.add(masked.isValid(i) ? date(unit, ExtensionStorage.epochInteger(masked.inner(), i)) : null);
            }
            return out;
        }
        for (int i = 0; i < n; i++) {
            out.add(date(unit, ExtensionStorage.epochInteger(storage, i)));
        }
        return out;
    }

    /// Rust stores an Arrow `Date32` as Days and a `Date64` as Milliseconds. A millisecond count
    /// floors to its day, so an instant before the epoch lands on the earlier date.
    private static LocalDate date(TimeUnit unit, long raw) {
        return switch (unit) {
            case Days -> LocalDate.ofEpochDay(raw);
            case Milliseconds -> LocalDate.ofEpochDay(Math.floorDiv(raw, MILLIS_PER_DAY));
            case Nanoseconds, Microseconds, Seconds ->
                    throw new VortexException("vortex.date does not support time unit " + unit);
        };
    }
}
