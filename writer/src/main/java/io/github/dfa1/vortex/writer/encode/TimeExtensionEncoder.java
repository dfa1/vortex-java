package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.extension.TimeDtype;
import io.github.dfa1.vortex.writer.ExtensionEncoder;

import java.time.LocalTime;
import java.util.Collection;

/// Write-side encoder for `vortex.time`: converts `Collection<LocalTime>`
/// to `int[]` (s/ms) or `long[]` (μs/ns) storage the writer accepts.
public final class TimeExtensionEncoder implements ExtensionEncoder {

    /// Singleton instance.
    public static final TimeExtensionEncoder INSTANCE = new TimeExtensionEncoder();

    /// Public no-arg constructor for [java.util.ServiceLoader].
    /// Prefer the {@link #INSTANCE} singleton in application code.
    public TimeExtensionEncoder() {
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

    @Override
    @SuppressWarnings("unchecked")
    public Object encodeAll(DType.Extension dtype, Collection<?> values) {
        TimeUnit unit = TimeDtype.readUnit(dtype);
        Collection<LocalTime> typed = (Collection<LocalTime>) values;
        int n = typed.size();
        boolean[] validity = new boolean[n];
        boolean anyNull = false;
        Object out;
        int i = 0;
        switch (unit) {
            case Seconds, Milliseconds -> {
                int[] arr = new int[n];
                for (LocalTime v : typed) {
                    if (v == null) {
                        anyNull = true;
                    } else {
                        arr[i] = Math.toIntExact(encode(v, unit));
                        validity[i] = true;
                    }
                    i++;
                }
                out = arr;
            }
            case Microseconds, Nanoseconds -> {
                long[] arr = new long[n];
                for (LocalTime v : typed) {
                    if (v == null) {
                        anyNull = true;
                    } else {
                        arr[i] = encode(v, unit);
                        validity[i] = true;
                    }
                    i++;
                }
                out = arr;
            }
            case Days -> throw new VortexException("Time.encodeAll: Days unit not valid for vortex.time");
            default -> throw new VortexException("unknown TimeUnit: " + unit);
        }
        if (!anyNull) {
            return out;
        }
        if (!dtype.nullable()) {
            throw new VortexException("null element in non-nullable vortex.time column");
        }
        return new NullableData(out, validity);
    }

    private static long encode(LocalTime value, TimeUnit unit) {
        if (unit == TimeUnit.Days) {
            throw new VortexException("Time.encode: Days unit not valid for vortex.time");
        }
        long divisor = 1_000_000_000L / unit.divisor();
        return value.toNanoOfDay() / divisor;
    }
}
