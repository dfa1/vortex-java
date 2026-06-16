package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.writer.ExtensionEncoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;

/// Write-side encoder for `vortex.timestamp`: converts `Collection<Instant>`
/// to the `long[]` storage layout the writer accepts.
public final class TimestampExtensionEncoder implements ExtensionEncoder {

    /// Singleton instance.
    public static final TimestampExtensionEncoder INSTANCE = new TimestampExtensionEncoder();

    /// Public no-arg constructor for {@link java.util.ServiceLoader}.
    /// Prefer the {@link #INSTANCE} singleton in application code.
    public TimestampExtensionEncoder() {
    }

    @Override
    public ExtensionId extensionId() {
        return ExtensionId.VORTEX_TIMESTAMP;
    }

    /// Returns the default dtype using milliseconds resolution and no timezone.
    /// Use {@link #dtype(TimeUnit, ZoneId, boolean)} for non-default settings.
    @Override
    public DType.Extension dtype(boolean nullable) {
        return dtype(TimeUnit.Milliseconds, null, nullable);
    }

    /// Returns the dtype for the given unit and timezone.
    ///
    /// @param unit     time resolution
    /// @param zone     IANA timezone, or `null` for none
    /// @param nullable whether the column allows nulls
    /// @return matching extension dtype
    public DType.Extension dtype(TimeUnit unit, ZoneId zone, boolean nullable) {
        byte[] tzBytes = zone == null ? new byte[0] : zone.getId().getBytes(StandardCharsets.UTF_8);
        ByteBuffer meta = ByteBuffer.allocate(3 + tzBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        meta.put(0, (byte) unit.ordinal());
        meta.putShort(1, (short) tzBytes.length);
        for (int k = 0; k < tzBytes.length; k++) {
            meta.put(3 + k, tzBytes[k]);
        }
        return new DType.Extension(
                ExtensionId.VORTEX_TIMESTAMP.id(),
                new DType.Primitive(PType.I64, nullable),
                meta,
                nullable);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object encodeAll(DType.Extension dtype, Collection<?> values) {
        TimeUnit unit = readUnit(dtype);
        Collection<Instant> typed = (Collection<Instant>) values;
        int n = typed.size();
        long[] out = new long[n];
        boolean[] validity = new boolean[n];
        boolean anyNull = false;
        int i = 0;
        for (Instant v : typed) {
            if (v == null) {
                anyNull = true;
            } else {
                out[i] = encode(v, unit);
                validity[i] = true;
            }
            i++;
        }
        if (!anyNull) {
            return out;
        }
        if (!dtype.nullable()) {
            throw new VortexException("null element in non-nullable vortex.timestamp column");
        }
        return new NullableData(out, validity);
    }

    private static TimeUnit readUnit(DType.Extension ext) {
        java.nio.ByteBuffer meta = ext.metadata();
        if (meta == null || !meta.hasRemaining()) {
            throw new VortexException("missing TimeUnit metadata byte for " + ext.extensionId());
        }
        return TimeUnit.fromTag(meta.get(meta.position()));
    }

    private static long encode(Instant value, TimeUnit unit) {
        return switch (unit) {
            case Seconds -> value.getEpochSecond();
            case Milliseconds -> value.toEpochMilli();
            case Microseconds -> Math.multiplyExact(value.getEpochSecond(), 1_000_000L)
                    + value.getNano() / 1_000L;
            case Nanoseconds -> Math.multiplyExact(value.getEpochSecond(), 1_000_000_000L)
                    + value.getNano();
            case Days -> throw new VortexException("Timestamp.encode: Days unit not valid");
        };
    }
}
