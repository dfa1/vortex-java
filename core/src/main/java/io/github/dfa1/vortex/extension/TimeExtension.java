package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.NullableData;
import io.github.dfa1.vortex.encoding.TimeUnit;

import java.nio.ByteBuffer;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// {@code vortex.time} — sub-day count in the {@link TimeUnit} recorded in the metadata byte.
public final class TimeExtension implements Extension {

    /// Singleton instance.
    public static final TimeExtension INSTANCE = new TimeExtension();

    /// Public no-arg constructor for {@link java.util.ServiceLoader}.
    /// Prefer the {@link #INSTANCE} singleton in application code.
    public TimeExtension() {
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

    /// Decodes the time-of-day cell at row {@code i}.
    ///
    /// @param ext     declared extension dtype carrying the {@link TimeUnit} byte
    /// @param storage signed-integer storage (I32 for s/ms, I64 for μs/ns)
    /// @param i       row index, {@code 0 <= i < storage.length()}
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

    /// Decodes every row of {@code storage} into a list of times. {@link io.github.dfa1.vortex.core.array.MaskedArray}
    /// storage yields {@code null} at invalid positions instead of throwing.
    ///
    /// @param ext     declared extension dtype carrying the unit
    /// @param storage signed-integer storage array (optionally wrapped in {@code MaskedArray})
    /// @return list of decoded times in row order; {@code null} entries mark invalid rows
    public List<LocalTime> decodeAll(DType.Extension ext, Array storage) {
        int n = Math.toIntExact(storage.length());
        List<LocalTime> out = new ArrayList<>(n);
        if (storage instanceof io.github.dfa1.vortex.core.array.MaskedArray masked) {
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

    /// Encodes a time-of-day at the given unit.
    ///
    /// @param value local time
    /// @param unit  resolution
    /// @return sub-day count in {@code unit}
    /// @throws VortexException if {@code unit} is {@link TimeUnit#Days}
    public long encode(LocalTime value, TimeUnit unit) {
        if (unit == TimeUnit.Days) {
            throw new VortexException("Time.encode: Days unit not valid for vortex.time");
        }
        long divisor = 1_000_000_000L / unit.divisor();
        return value.toNanoOfDay() / divisor;
    }

    /// Encodes a collection of times into the storage layout matching the unit:
    /// {@code int[]} for {@link TimeUnit#Seconds}/{@link TimeUnit#Milliseconds},
    /// {@code long[]} for {@link TimeUnit#Microseconds}/{@link TimeUnit#Nanoseconds}.
    /// Return type is {@code Object} so the writer can switch on the array type.
    ///
    /// @param values times to encode
    /// @param unit   resolution; controls storage width
    /// @return {@code int[]} or {@code long[]} suitable for {@code writer.writeChunk}
    /// @throws VortexException if {@code unit} is {@link TimeUnit#Days}
    public Object encodeAll(Collection<LocalTime> values, TimeUnit unit) {
        return switch (unit) {
            case Seconds, Milliseconds -> {
                int[] out = new int[values.size()];
                int i = 0;
                for (LocalTime v : values) {
                    out[i++] = Math.toIntExact(encode(v, unit));
                }
                yield out;
            }
            case Microseconds, Nanoseconds -> {
                long[] out = new long[values.size()];
                int i = 0;
                for (LocalTime v : values) {
                    out[i++] = encode(v, unit);
                }
                yield out;
            }
            case Days -> throw new VortexException("Time.encodeAll: Days unit not valid for vortex.time");
        };
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
}
