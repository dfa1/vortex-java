package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.NullableData;
import io.github.dfa1.vortex.extension.ExtensionEncoder;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.extension.ExtensionStorage;
import io.github.dfa1.vortex.extension.TimeExtension;
import io.github.dfa1.vortex.encoding.TimeUnit;

import java.time.LocalTime;
import java.util.Collection;

/// Write-side encoder for {@code vortex.time}: converts {@code Collection<LocalTime>}
/// to {@code int[]} (s/ms) or {@code long[]} (μs/ns) storage the writer accepts.
public final class TimeExtensionEncoder implements ExtensionEncoder {

    /// Singleton instance.
    public static final TimeExtensionEncoder INSTANCE = new TimeExtensionEncoder();

    /// Public no-arg constructor for {@link java.util.ServiceLoader}.
    /// Prefer the {@link #INSTANCE} singleton in application code.
    public TimeExtensionEncoder() {
    }

    @Override
    public ExtensionId extensionId() {
        return ExtensionId.VORTEX_TIME;
    }

    @Override
    public DType.Extension dtype(boolean nullable) {
        return TimeExtension.INSTANCE.dtype(nullable);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object encodeAll(DType.Extension dtype, Collection<?> values) {
        TimeUnit unit = ExtensionStorage.readUnit(dtype);
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
                        arr[i] = Math.toIntExact(TimeExtension.INSTANCE.encode(v, unit));
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
                        arr[i] = TimeExtension.INSTANCE.encode(v, unit);
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
}
