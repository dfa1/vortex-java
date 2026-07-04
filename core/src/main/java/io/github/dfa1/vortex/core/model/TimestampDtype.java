package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
        MemorySegment meta = MemorySegment.ofArray(new byte[3 + tzBytes.length]);
        meta.set(ValueLayout.JAVA_BYTE, 0, (byte) unit.ordinal());
        meta.set(LE_SHORT, 1, (short) tzBytes.length);
        MemorySegment.copy(tzBytes, 0, meta, ValueLayout.JAVA_BYTE, 3, tzBytes.length);
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
        MemorySegment meta = ext.metadata();
        if (meta == null || meta.byteSize() == 0) {
            throw new VortexException("missing TimeUnit metadata byte for " + ext.extensionId());
        }
        return TimeUnit.fromTag(meta.get(ValueLayout.JAVA_BYTE, 0));
    }

    /// Reads the optional IANA timezone from the metadata's UTF-8 suffix.
    ///
    /// @param ext declared extension dtype
    /// @return parsed zone id, or empty when no timezone is recorded
    /// @throws VortexException if the metadata is truncated mid-string
    public static Optional<ZoneId> timezone(DType.Extension ext) {
        MemorySegment meta = ext.metadata();
        if (meta == null || meta.byteSize() < 3) {
            return Optional.empty();
        }
        int tzLen = Short.toUnsignedInt(meta.get(LE_SHORT, 1));
        if (tzLen == 0) {
            return Optional.empty();
        }
        if (meta.byteSize() < 3 + tzLen) {
            throw new VortexException(
                    "timestamp metadata truncated: declared tz_len="
                            + tzLen + " but only " + (meta.byteSize() - 3) + " bytes available");
        }
        byte[] tzBytes = meta.asSlice(3, tzLen).toArray(ValueLayout.JAVA_BYTE);
        return Optional.of(ZoneId.of(new String(tzBytes, StandardCharsets.UTF_8)));
    }
}
