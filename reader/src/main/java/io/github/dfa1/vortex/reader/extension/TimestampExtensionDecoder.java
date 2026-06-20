package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.IoBounds;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.extension.TimestampDtype;

import io.github.dfa1.vortex.reader.ExtensionDecoder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// `vortex.timestamp` — I64 epoch count plus optional IANA timezone.
public final class TimestampExtensionDecoder implements ExtensionDecoder {

    /// Singleton instance.
    public static final TimestampExtensionDecoder INSTANCE = new TimestampExtensionDecoder();

    /// Public no-arg constructor for [java.util.ServiceLoader].
    /// Prefer the [#INSTANCE] singleton in application code.
    public TimestampExtensionDecoder() {
    }

    @Override
    public ExtensionId extensionId() {
        return ExtensionId.VORTEX_TIMESTAMP;
    }

    /// Returns the default dtype using milliseconds resolution and no timezone.
    /// Use [#dtype(TimeUnit, ZoneId, boolean)] for non-default settings.
    @Override
    public DType.Extension dtype(boolean nullable) {
        return TimestampDtype.of(nullable);
    }

    /// Returns the dtype for the given unit and timezone.
    ///
    /// @param unit     time resolution
    /// @param zone     IANA timezone, or `null` for none
    /// @param nullable whether the column allows nulls
    /// @return matching extension dtype
    public DType.Extension dtype(TimeUnit unit, ZoneId zone, boolean nullable) {
        return TimestampDtype.of(unit, zone, nullable);
    }

    /// Decodes the timestamp cell at row `i` to an [Instant], ignoring timezone.
    ///
    /// @param ext     declared extension dtype
    /// @param storage signed-integer storage array
    /// @param i       row index, `0 <= i < storage.length()`
    /// @return decoded instant
    /// @throws VortexException if the metadata unit is [TimeUnit#Days]
    ///         or storage isn't an integer primitive
    public Instant instant(DType.Extension ext, Array storage, long i) {
        ExtensionStorage.checkBounds(i, storage.length());
        TimeUnit unit = ExtensionStorage.readUnit(ext);
        if (unit == TimeUnit.Days) {
            throw new VortexException("Timestamp.instant: Days unit not valid");
        }
        return ExtensionStorage.instantFromRaw(ExtensionStorage.epochInteger(storage, i), unit);
    }

    /// Decodes the timestamp cell at row `i` to a [ZonedDateTime]
    /// using the timezone from the metadata, defaulting to UTC when absent.
    ///
    /// @param ext     declared extension dtype
    /// @param storage signed-integer storage array
    /// @param i       row index, `0 <= i < storage.length()`
    /// @return decoded zoned date-time
    public ZonedDateTime zonedDateTime(DType.Extension ext, Array storage, long i) {
        return instant(ext, storage, i).atZone(timezone(ext).orElse(ZoneOffset.UTC));
    }

    /// Returns the IANA timezone recorded in the extension metadata.
    ///
    /// @param ext declared extension dtype
    /// @return parsed zone id, or empty when no timezone is recorded
    /// @throws VortexException if the metadata is truncated mid-string
    public Optional<ZoneId> timezone(DType.Extension ext) {
        return TimestampDtype.timezone(ext);
    }

    /// Decodes every row of `storage` into a list of instants. [MaskedArray]
    /// storage yields `null` at invalid positions instead of throwing.
    ///
    /// @param ext     declared extension dtype carrying the unit
    /// @param storage signed-integer storage array (optionally wrapped in `MaskedArray`)
    /// @return list of decoded instants in row order; `null` entries mark invalid rows
    public List<Instant> decodeAll(DType.Extension ext, Array storage) {
        int n = IoBounds.toIntSize(storage.length());
        List<Instant> out = new ArrayList<>(n);
        if (storage instanceof MaskedArray masked) {
            for (int i = 0; i < n; i++) {
                out.add(masked.isValid(i) ? instant(ext, masked.inner(), i) : null);
            }
            return out;
        }
        for (int i = 0; i < n; i++) {
            out.add(instant(ext, storage, i));
        }
        return out;
    }
}
