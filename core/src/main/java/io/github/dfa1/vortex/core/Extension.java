package io.github.dfa1.vortex.core;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.FixedSizeListArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.encoding.TimeUnit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

/// Sealed hierarchy of Vortex extension dtypes — closed-world view of the
/// four spec-defined extensions ({@code vortex.date}, {@code vortex.time},
/// {@code vortex.timestamp}, {@code vortex.uuid}) plus a {@link Custom}
/// fallback record carrying any other id.
///
/// <p>Mirrors the {@link io.github.dfa1.vortex.encoding.Encoding} /
/// {@link io.github.dfa1.vortex.encoding.EncodingId} pairing in spirit but
/// merges the kind classification with the typed decode behaviour: each
/// record exposes its own statically-typed decode methods rather than a
/// single {@code Object decode(...)} contract that callers would have to
/// downcast. Pattern-match exhaustively to dispatch:
///
/// ```java
/// switch (ext.kind()) {
///     case Extension.Date d      -> d.decode(storage, i);          // LocalDate
///     case Extension.Time t      -> t.decode(ext, storage, i);     // LocalTime
///     case Extension.Timestamp ts -> ts.instant(ext, storage, i);  // Instant
///     case Extension.Uuid u      -> u.decode(storage, i);          // UUID
///     case Extension.Custom c    -> renderPlaceholder(c.id());
/// }
/// ```
///
/// <p>{@link DType.Extension} carries the wire-format id as a {@code String}
/// so unknown ids round-trip without loss; {@link #of(String)} translates to
/// the matching record.
public sealed interface Extension {

    /// Singleton for {@link Date}.
    Date DATE = new Date();
    /// Singleton for {@link Time}.
    Time TIME = new Time();
    /// Singleton for {@link Timestamp}.
    Timestamp TIMESTAMP = new Timestamp();
    /// Singleton for {@link Uuid}.
    Uuid UUID = new Uuid();

    /// Returns the wire-format id string.
    ///
    /// @return canonical extension id
    String id();

    /// Resolves a wire-format id string to its {@link Extension} record.
    /// Unknown ids land in {@link Custom}.
    ///
    /// @param id raw extension id from the file footer
    /// @return matching record, or {@link Custom} when {@code id} isn't recognised
    static Extension of(String id) {
        return switch (id) {
            case Date.ID -> DATE;
            case Time.ID -> TIME;
            case Timestamp.ID -> TIMESTAMP;
            case Uuid.ID -> UUID;
            default -> new Custom(id);
        };
    }

    /// {@code vortex.date} — days (any signed integer width) since the
    /// Unix epoch. Per Arrow's canonical Date type.
    final class Date implements Extension {
        /// Wire id.
        public static final String ID = "vortex.date";

        private Date() {
        }

        @Override public String id() {
            return ID;
        }

        /// Decodes the date cell at row {@code i}.
        ///
        /// @param storage signed-integer storage (Byte/Short/Int/Long, possibly Masked)
        /// @param i       row index, {@code 0 <= i < storage.length()}
        /// @return decoded date
        /// @throws VortexException if storage isn't an integer primitive
        public LocalDate decode(Array storage, long i) {
            checkBounds(i, storage.length());
            return LocalDate.ofEpochDay(epochInteger(storage, i));
        }
    }

    /// {@code vortex.time} — sub-day count in the {@link TimeUnit} recorded
    /// in {@code ext.metadata()} byte 0.
    final class Time implements Extension {
        /// Wire id.
        public static final String ID = "vortex.time";

        private Time() {
        }

        @Override public String id() {
            return ID;
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
            checkBounds(i, storage.length());
            TimeUnit unit = readUnit(ext);
            if (unit == TimeUnit.Days) {
                throw new VortexException("Time.decode: Days unit not valid for vortex.time");
            }
            long raw = epochInteger(storage, i);
            long nanos = raw * (1_000_000_000L / unit.divisor());
            return LocalTime.ofNanoOfDay(nanos);
        }

    }

    /// {@code vortex.timestamp} — I64 epoch count plus optional IANA timezone.
    /// Metadata layout: {@code byte[0] = TimeUnit tag, bytes[1..3] = tz_len
    /// (u16 LE), bytes[3..3+tz_len] = tz UTF-8}.
    final class Timestamp implements Extension {
        /// Wire id.
        public static final String ID = "vortex.timestamp";

        private Timestamp() {
        }

        @Override public String id() {
            return ID;
        }

        /// Decodes the timestamp cell at row {@code i} to an {@link Instant},
        /// ignoring any timezone the metadata carries.
        ///
        /// @param ext     declared extension dtype
        /// @param storage signed-integer storage array
        /// @param i       row index, {@code 0 <= i < storage.length()}
        /// @return decoded instant
        /// @throws VortexException if the metadata unit is {@link TimeUnit#Days}
        ///         or storage isn't an integer primitive
        public Instant instant(DType.Extension ext, Array storage, long i) {
            checkBounds(i, storage.length());
            TimeUnit unit = readUnit(ext);
            if (unit == TimeUnit.Days) {
                throw new VortexException("Timestamp.instant: Days unit not valid");
            }
            return instantFromRaw(epochInteger(storage, i), unit);
        }

        /// Decodes the timestamp cell at row {@code i} to a {@link ZonedDateTime}
        /// using the timezone from the metadata, defaulting to UTC when absent.
        ///
        /// @param ext     declared extension dtype
        /// @param storage signed-integer storage array
        /// @param i       row index, {@code 0 <= i < storage.length()}
        /// @return decoded zoned date-time
        public ZonedDateTime zonedDateTime(DType.Extension ext, Array storage, long i) {
            return instant(ext, storage, i).atZone(timezone(ext).orElse(ZoneOffset.UTC));
        }

        /// Returns the IANA timezone string recorded in the extension metadata.
        ///
        /// @param ext declared extension dtype
        /// @return parsed zone id, or empty when {@code tz_len == 0}
        /// @throws VortexException if the metadata is truncated mid-string
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
                throw new VortexException("timestamp metadata truncated: declared tz_len="
                        + tzLen + " but only " + (le.remaining() - 3) + " bytes available");
            }
            byte[] tzBytes = new byte[tzLen];
            for (int k = 0; k < tzLen; k++) {
                tzBytes[k] = le.get(basePos + 3 + k);
            }
            return Optional.of(ZoneId.of(new String(tzBytes, StandardCharsets.UTF_8)));
        }

    }

    /// {@code vortex.uuid} — 16-byte UUID stored as
    /// {@code FixedSizeList(Primitive(U8), 16)}.
    final class Uuid implements Extension {
        /// Wire id.
        public static final String ID = "vortex.uuid";

        private Uuid() {
        }

        @Override public String id() {
            return ID;
        }

        /// Decodes the UUID cell at row {@code i}.
        ///
        /// @param storage UUID storage array
        /// @param i       row index, {@code 0 <= i < storage.length()}
        /// @return decoded {@link java.util.UUID}
        /// @throws VortexException if storage isn't a {@code FixedSizeListArray<ByteArray>}
        ///         of size 16
        public java.util.UUID decode(Array storage, long i) {
            checkBounds(i, storage.length());
            if (!(storage instanceof FixedSizeListArray fsl)) {
                throw new VortexException("Uuid.decode: expected FixedSizeListArray, got "
                        + storage.getClass().getSimpleName());
            }
            if (fsl.fixedSize() != 16) {
                throw new VortexException("Uuid.decode: expected fixedSize 16, got " + fsl.fixedSize());
            }
            if (!(fsl.elements() instanceof ByteArray bytes)) {
                throw new VortexException("Uuid.decode: expected ByteArray elements, got "
                        + fsl.elements().getClass().getSimpleName());
            }
            long base = i * 16;
            long msb = 0L;
            long lsb = 0L;
            for (int k = 0; k < 8; k++) {
                msb = (msb << 8) | (bytes.getByte(base + k) & 0xffL);
            }
            for (int k = 0; k < 8; k++) {
                lsb = (lsb << 8) | (bytes.getByte(base + 8 + k) & 0xffL);
            }
            return new java.util.UUID(msb, lsb);
        }
    }

    /// Open-world escape hatch for any extension id Vortex-java doesn't
    /// know about. Pattern-match branches that need to render or decode an
    /// unknown extension read its raw id via {@link #id()}.
    ///
    /// @param id raw extension id string
    record Custom(String id) implements Extension {
    }

    // ── Shared helpers ────────────────────────────────────────────────────

    /// Reads a signed integer from any of the integer primitive arrays as
    /// {@code long}. Recurses through {@link MaskedArray}; throws on null
    /// cells so callers don't silently get garbage for nullable columns.
    private static long epochInteger(Array storage, long i) {
        return switch (storage) {
            case ByteArray a -> a.getByte(i);
            case ShortArray a -> a.getShort(i);
            case IntArray a -> a.getInt(i);
            case LongArray a -> a.getLong(i);
            case MaskedArray a -> {
                if (!a.isValid(i)) {
                    throw new VortexException("null cell at index " + i);
                }
                yield epochInteger(a.inner(), i);
            }
            default -> throw new VortexException(
                    "unsupported storage type " + storage.getClass().getSimpleName());
        };
    }

    /// Reads the {@link TimeUnit} metadata byte at the buffer's current
    /// position; throws if the buffer is null or empty.
    private static TimeUnit readUnit(DType.Extension ext) {
        ByteBuffer meta = ext.metadata();
        if (meta == null || !meta.hasRemaining()) {
            throw new VortexException("missing TimeUnit metadata byte for " + ext.extensionId());
        }
        return TimeUnit.fromTag(meta.get(meta.position()));
    }

    private static Instant instantFromRaw(long raw, TimeUnit unit) {
        return switch (unit) {
            case Seconds -> Instant.ofEpochSecond(raw);
            case Milliseconds -> Instant.ofEpochMilli(raw);
            case Microseconds -> {
                long secs = Math.floorDiv(raw, 1_000_000L);
                long nanos = Math.floorMod(raw, 1_000_000L) * 1_000L;
                yield Instant.ofEpochSecond(secs, nanos);
            }
            case Nanoseconds -> {
                long secs = Math.floorDiv(raw, 1_000_000_000L);
                long nanos = Math.floorMod(raw, 1_000_000_000L);
                yield Instant.ofEpochSecond(secs, nanos);
            }
            case Days -> throw new VortexException("Days unit not valid for instant");
        };
    }

    private static void checkBounds(long i, long length) {
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("index " + i + " out of bounds for length " + length);
        }
    }
}
