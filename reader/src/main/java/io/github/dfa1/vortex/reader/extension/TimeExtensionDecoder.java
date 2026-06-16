package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.extension.TimeDtype;

import io.github.dfa1.vortex.reader.ExtensionDecoder;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/// `vortex.time` — sub-day count in the {@link TimeUnit} recorded in the metadata byte.
public final class TimeExtensionDecoder implements ExtensionDecoder {

    /// Singleton instance.
    public static final TimeExtensionDecoder INSTANCE = new TimeExtensionDecoder();

    /// Public no-arg constructor for {@link java.util.ServiceLoader}.
    /// Prefer the {@link #INSTANCE} singleton in application code.
    public TimeExtensionDecoder() {
    }

    @Override
    public ExtensionId extensionId() {
        return ExtensionId.VORTEX_TIME;
    }

    /// Returns the default dtype using [TimeUnit#Milliseconds] over I32 storage.
    /// Use [#dtype(TimeUnit, boolean)] for non-default units.
    @Override
    public DType.Extension dtype(boolean nullable) {
        return TimeDtype.of(nullable);
    }

    /// Returns the dtype for the given [TimeUnit].
    ///
    /// @param unit     time resolution; controls storage width (I32 for s/ms, I64 for μs/ns)
    /// @param nullable whether the column allows nulls
    /// @return matching extension dtype
    public DType.Extension dtype(TimeUnit unit, boolean nullable) {
        return TimeDtype.of(unit, nullable);
    }

    /// Decodes the time-of-day cell at row `i`.
    ///
    /// @param ext     declared extension dtype carrying the {@link TimeUnit} byte
    /// @param storage signed-integer storage (I32 for s/ms, I64 for μs/ns)
    /// @param i       row index, `0 <= i < storage.length()`
    /// @return decoded local time
    /// @throws VortexException if the metadata unit is {@link TimeUnit#Days}
    ///         or storage isn't an integer primitive
    public LocalTime decode(DType.Extension ext, Array storage, long i) {
        ExtensionStorage.checkBounds(i, storage.length());
        TimeUnit unit = ExtensionStorage.readUnit(ext);
        if (unit == TimeUnit.Days) {
            throw new VortexException("Time.decode: Days unit not valid for vortex.time");
        }
        long raw = ExtensionStorage.epochInteger(storage, i);
        long nanos = raw * (1_000_000_000L / unit.divisor());
        return LocalTime.ofNanoOfDay(nanos);
    }

    /// Decodes every row of `storage` into a list of times. {@link MaskedArray}
    /// storage yields `null` at invalid positions instead of throwing.
    ///
    /// @param ext     declared extension dtype carrying the unit
    /// @param storage signed-integer storage array (optionally wrapped in `MaskedArray`)
    /// @return list of decoded times in row order; `null` entries mark invalid rows
    public List<LocalTime> decodeAll(DType.Extension ext, Array storage) {
        int n = Math.toIntExact(storage.length());
        List<LocalTime> out = new ArrayList<>(n);
        if (storage instanceof MaskedArray masked) {
            for (long i = 0; i < n; i++) {
                out.add(masked.isValid(i) ? decode(ext, masked.inner(), i) : null);
            }
            return out;
        }
        for (long i = 0; i < n; i++) {
            out.add(decode(ext, storage, i));
        }
        return out;
    }
}
