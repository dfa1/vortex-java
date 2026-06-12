package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.writer.ExtensionEncoder;

import java.nio.ByteBuffer;
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

    /// Returns the default dtype using {@link TimeUnit#Milliseconds} over I32 storage.
    /// Use {@link #dtype(TimeUnit, boolean)} for non-default units.
    @Override
    public DType.Extension dtype(boolean nullable) {
        return dtype(TimeUnit.Milliseconds, nullable);
    }

    /// Returns the dtype for the given {@link TimeUnit}.
    ///
    /// @param unit     time resolution; controls storage width (I32 for s/ms, I64 for μs/ns)
    /// @param nullable whether the column allows nulls
    /// @return matching extension dtype
    public DType.Extension dtype(TimeUnit unit, boolean nullable) {
        PType storage = switch (unit) {
            case Seconds, Milliseconds -> PType.I32;
            case Microseconds, Nanoseconds -> PType.I64;
            case Days -> throw new IllegalArgumentException("Days unit not valid for vortex.time");
        };
        ByteBuffer meta = ByteBuffer.allocate(1);
        meta.put(0, (byte) unit.ordinal());
        return new DType.Extension(
                ExtensionId.VORTEX_TIME.id(),
                new DType.Primitive(storage, nullable),
                meta,
                nullable);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object encodeAll(DType.Extension dtype, Collection<?> values) {
        TimeUnit unit = readUnit(dtype);
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

    private static TimeUnit readUnit(DType.Extension ext) {
        ByteBuffer meta = ext.metadata();
        if (meta == null || !meta.hasRemaining()) {
            throw new VortexException("missing TimeUnit metadata byte for " + ext.extensionId());
        }
        return TimeUnit.fromTag(meta.get(meta.position()));
    }

    private static long encode(LocalTime value, TimeUnit unit) {
        if (unit == TimeUnit.Days) {
            throw new VortexException("Time.encode: Days unit not valid for vortex.time");
        }
        long divisor = 1_000_000_000L / unit.divisor();
        return value.toNanoOfDay() / divisor;
    }
}
