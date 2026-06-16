package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.TimeUnit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Optional;

/// Static factories and metadata accessors for `vortex.timestamp` extension dtypes.
///
/// Wire format: `byte[0] = TimeUnit tag, bytes[1..3] = tz_len (u16 LE),
/// bytes[3..3+tz_len] = tz UTF-8`. Shared by reader and writer so the
/// encode/decode sides cannot drift.
public final class TimestampDtype {

    private TimestampDtype() {
    }

    /// Default dtype: milliseconds resolution, no timezone.
    ///
    /// @param nullable whether the column allows nulls
    /// @return matching extension dtype
    public static DType.Extension of(boolean nullable) {
        return of(TimeUnit.Milliseconds, null, nullable);
    }

    /// Returns the extension dtype for the given unit and timezone.
    ///
    /// @param unit     time resolution
    /// @param zone     IANA timezone, or `null` for none
    /// @param nullable whether the column allows nulls
    /// @return matching extension dtype
    public static DType.Extension of(TimeUnit unit, ZoneId zone, boolean nullable) {
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

    /// Reads the [TimeUnit] tag from the metadata's first byte.
    ///
    /// @param ext declared extension dtype
    /// @return the recorded time unit
    /// @throws VortexException if metadata is missing or empty
    public static TimeUnit readUnit(DType.Extension ext) {
        ByteBuffer meta = ext.metadata();
        if (meta == null || !meta.hasRemaining()) {
            throw new VortexException("missing TimeUnit metadata byte for " + ext.extensionId());
        }
        return TimeUnit.fromTag(meta.get(meta.position()));
    }

    /// Reads the optional IANA timezone from the metadata's UTF-8 suffix.
    ///
    /// @param ext declared extension dtype
    /// @return parsed zone id, or empty when no timezone is recorded
    /// @throws VortexException if the metadata is truncated mid-string
    public static Optional<ZoneId> timezone(DType.Extension ext) {
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
            throw new VortexException(
                    "timestamp metadata truncated: declared tz_len="
                            + tzLen + " but only " + (le.remaining() - 3) + " bytes available");
        }
        byte[] tzBytes = new byte[tzLen];
        for (int k = 0; k < tzLen; k++) {
            tzBytes[k] = le.get(basePos + 3 + k);
        }
        return Optional.of(ZoneId.of(new String(tzBytes, StandardCharsets.UTF_8)));
    }
}
