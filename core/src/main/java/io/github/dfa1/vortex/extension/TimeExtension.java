package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.encoding.TimeUnit;

import java.nio.ByteBuffer;
import java.time.LocalTime;

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
}
