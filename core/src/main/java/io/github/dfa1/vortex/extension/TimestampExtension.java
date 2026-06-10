package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.NullableData;
import io.github.dfa1.vortex.encoding.TimeUnit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/// {@code vortex.timestamp} — I64 epoch count plus optional IANA timezone.
/// Metadata layout: {@code byte[0] = TimeUnit tag, bytes[1..3] = tz_len (u16 LE),
/// bytes[3..3+tz_len] = tz UTF-8}.
public final class TimestampExtension implements Extension {

    /// Singleton instance.
    public static final TimestampExtension INSTANCE = new TimestampExtension();

    /// Public no-arg constructor for {@link java.util.ServiceLoader}.
    /// Prefer the {@link #INSTANCE} singleton in application code.
    public TimestampExtension() {
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
    /// @param zone     IANA timezone, or {@code null} for none
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

    /// Decodes the timestamp cell at row {@code i} to an {@link Instant}, ignoring timezone.
    ///
    /// @param ext     declared extension dtype
    /// @param storage signed-integer storage array
    /// @param i       row index, {@code 0 <= i < storage.length()}
    /// @return decoded instant
    /// @throws io.github.dfa1.vortex.core.VortexException if the metadata unit is
    ///         {@link TimeUnit#Days} or storage isn't an integer primitive
    public Instant instant(DType.Extension ext, Array storage, long i) {
        ExtensionStorage.checkBounds(i, storage.length());
        TimeUnit unit = ExtensionStorage.readUnit(ext);
        if (unit == TimeUnit.Days) {
            throw new io.github.dfa1.vortex.core.VortexException("Timestamp.instant: Days unit not valid");
        }
        return ExtensionStorage.instantFromRaw(ExtensionStorage.epochInteger(storage, i), unit);
    }

    /// Decodes the timestamp cell at row {@code i} to a {@link ZonedDateTime}
    /// using the timezone from the metadata, defaulting to UTC when absent.
    ///
    /// @param ext     declared extension dtype
    /// @param storage signed-integer storage array
    /// @param i       row index, {@code 0 <= i < storage.length()}
    /// @return decoded zoned date-time
    public ZonedDateTime zonedDateTime(DType.Extension ext, Array storage, long i) {
        return instant(ext, storage, i).atZone(timezone(ext).orElse(java.time.ZoneOffset.UTC));
    }

    /// Returns the IANA timezone recorded in the extension metadata.
    ///
    /// @param ext declared extension dtype
    /// @return parsed zone id, or empty when no timezone is recorded
    /// @throws io.github.dfa1.vortex.core.VortexException if the metadata is truncated mid-string
    public Optional<ZoneId> timezone(DType.Extension ext) {
        ByteBuffer meta = ext.metadata();
        if (meta == null || meta.remaining() < 3) {
            return Optional.empty();
        }
        ByteBuffer le = meta.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int basePos = le.position();
        int tzLen = Short.toUnsignedInt(le.getShort(basePos + 1));
        if (tzLen == 0) {
            return Optional.empty();
        }
        if (le.remaining() < 3 + tzLen) {
            throw new io.github.dfa1.vortex.core.VortexException(
                    "timestamp metadata truncated: declared tz_len="
                            + tzLen + " but only " + (le.remaining() - 3) + " bytes available");
        }
        byte[] tzBytes = new byte[tzLen];
        for (int k = 0; k < tzLen; k++) {
            tzBytes[k] = le.get(basePos + 3 + k);
        }
        return Optional.of(ZoneId.of(new String(tzBytes, StandardCharsets.UTF_8)));
    }

    /// Decodes every row of {@code storage} into a list of instants. {@link io.github.dfa1.vortex.core.array.MaskedArray}
    /// storage yields {@code null} at invalid positions instead of throwing.
    ///
    /// @param ext     declared extension dtype carrying the unit
    /// @param storage signed-integer storage array (optionally wrapped in {@code MaskedArray})
    /// @return list of decoded instants in row order; {@code null} entries mark invalid rows
    public List<Instant> decodeAll(DType.Extension ext, Array storage) {
        int n = Math.toIntExact(storage.length());
        List<Instant> out = new ArrayList<>(n);
        if (storage instanceof io.github.dfa1.vortex.core.array.MaskedArray masked) {
            for (long i = 0; i < n; i++) {
                out.add(masked.isValid(i) ? instant(ext, masked.inner(), i) : null);
            }
            return out;
        }
        for (long i = 0; i < n; i++) {
            out.add(instant(ext, storage, i));
        }
        return out;
    }

    /// Encodes an instant at the given unit.
    ///
    /// @param value instant
    /// @param unit  resolution
    /// @return epoch count in {@code unit}
    /// @throws io.github.dfa1.vortex.core.VortexException if {@code unit} is {@link TimeUnit#Days}
    public long encode(Instant value, TimeUnit unit) {
        return switch (unit) {
            case Seconds -> value.getEpochSecond();
            case Milliseconds -> value.toEpochMilli();
            case Microseconds -> Math.multiplyExact(value.getEpochSecond(), 1_000_000L)
                    + value.getNano() / 1_000L;
            case Nanoseconds -> Math.multiplyExact(value.getEpochSecond(), 1_000_000_000L)
                    + value.getNano();
            case Days -> throw new io.github.dfa1.vortex.core.VortexException(
                    "Timestamp.encode: Days unit not valid");
        };
    }

    /// Encodes a collection of instants into a packed {@code long[]} matching
    /// the I64 storage layout the writer accepts.
    ///
    /// @param values instants to encode
    /// @param unit   resolution
    /// @return packed {@code long[]} suitable for {@code writer.writeChunk}
    public long[] encodeAll(Collection<Instant> values, TimeUnit unit) {
        long[] out = new long[values.size()];
        int i = 0;
        for (Instant v : values) {
            out[i++] = encode(v, unit);
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object encodeAll(DType.Extension dtype, Collection<?> values) {
        TimeUnit unit = ExtensionStorage.readUnit(dtype);
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
}
