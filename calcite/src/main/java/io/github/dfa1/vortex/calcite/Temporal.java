package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;

/// A `vortex.date`, `vortex.time` or `vortex.timestamp` column's conversion from its raw storage
/// integer to Calcite's internal form: `DATE` as epoch days (`Integer`), `TIME` as milliseconds of
/// the day (`Integer`), `TIMESTAMP` as epoch milliseconds (`Long`).
///
/// Resolved once per column, so the scan neither parses the extension metadata nor builds a
/// `LocalDate`/`LocalTime`/`Instant` per row, and the aggregate rule converts a zone statistic,
/// which is the raw storage value, the same way. Rounding matches the reader's decoders: a
/// millisecond date floors to its day, a timestamp floors to its millisecond.
///
/// @param kind which temporal extension the column is
/// @param unit the column's storage unit
record Temporal(ExtensionId.WellKnown kind, TimeUnit unit) {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    /// The conversion for a column of `type`, or `null` when it is not a temporal extension.
    ///
    /// @throws VortexException for a unit the extension does not allow, as the decoders do
    static Temporal of(DType type) {
        if (!(type instanceof DType.Extension ext)
                || !(ExtensionId.parse(ext.extensionId()) instanceof ExtensionId.WellKnown kind)
                || kind != ExtensionId.WellKnown.VORTEX_DATE && kind != ExtensionId.WellKnown.VORTEX_TIME
                && kind != ExtensionId.WellKnown.VORTEX_TIMESTAMP) {
            return null;
        }
        // the unit byte leads every temporal extension's metadata
        TimeUnit unit = TimestampDtype.readUnit(ext);
        boolean valid = switch (kind) {
            case VORTEX_DATE -> unit == TimeUnit.Days || unit == TimeUnit.Milliseconds;
            default -> unit != TimeUnit.Days;
        };
        if (!valid) {
            throw new VortexException(ext.extensionId() + " does not support time unit " + unit);
        }
        return new Temporal(kind, unit);
    }

    /// Converts one raw storage value.
    Object convert(long raw) {
        return switch (kind) {
            case VORTEX_DATE -> Math.toIntExact(unit == TimeUnit.Days ? raw : Math.floorDiv(raw, MILLIS_PER_DAY));
            case VORTEX_TIME -> {
                long millis = unit == TimeUnit.Seconds ? raw * 1000 : raw / (unit.divisor() / 1000);
                if (raw < 0 || millis >= MILLIS_PER_DAY) {
                    throw new VortexException("vortex.time value " + raw + " " + unit + " is outside a day");
                }
                yield (int) millis;
            }
            default -> unit == TimeUnit.Seconds ? Math.multiplyExact(raw, 1000)
                    : Math.floorDiv(raw, unit.divisor() / 1000);
        };
    }

    /// Reads row `r` of the column's storage array (possibly masked) and converts it.
    ///
    /// @return the converted value, or `null` for a null row
    Object read(Object array, long r) {
        if (array instanceof MaskedArray masked) {
            return masked.isValid(r) ? read(masked.inner(), r) : null;
        }
        long raw = switch (array) {
            case LongArray a -> a.getLong(r);
            case IntArray a -> a.getInt(r);
            case ShortArray a -> a.getShort(r);
            case ByteArray a -> a.getByte(r);
            default -> throw new VortexException("temporal storage must be a signed integer, got "
                    + array.getClass().getSimpleName());
        };
        return convert(raw);
    }
}
