package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.encoding.TimeUnit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/// {@code vortex.timestamp} — I64 epoch count plus optional IANA timezone.
/// Metadata layout: {@code byte[0] = TimeUnit tag, bytes[1..3] = tz_len (u16 LE),
/// bytes[3..3+tz_len] = tz UTF-8}.
public final class TimestampExtension implements Extension {

    /// Singleton instance.
    public static final TimestampExtension INSTANCE = new TimestampExtension();

    private TimestampExtension() {
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
    public Instant instant(DType.Extension ext, Array storage, long i) {
        return io.github.dfa1.vortex.core.Extension.TIMESTAMP.instant(ext, storage, i);
    }

    /// Decodes the timestamp cell at row {@code i} to a {@link ZonedDateTime}
    /// using the timezone from the metadata, defaulting to UTC when absent.
    ///
    /// @param ext     declared extension dtype
    /// @param storage signed-integer storage array
    /// @param i       row index, {@code 0 <= i < storage.length()}
    /// @return decoded zoned date-time
    public ZonedDateTime zonedDateTime(DType.Extension ext, Array storage, long i) {
        return io.github.dfa1.vortex.core.Extension.TIMESTAMP.zonedDateTime(ext, storage, i);
    }

    /// Returns the IANA timezone recorded in the extension metadata.
    ///
    /// @param ext declared extension dtype
    /// @return parsed zone id, or empty when no timezone is recorded
    public Optional<ZoneId> timezone(DType.Extension ext) {
        return io.github.dfa1.vortex.core.Extension.TIMESTAMP.timezone(ext);
    }
}
