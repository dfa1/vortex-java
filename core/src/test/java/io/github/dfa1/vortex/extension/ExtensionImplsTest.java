package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.FixedSizeListArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.encoding.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionImplsTest {

    private static final DType.Primitive I32 = new DType.Primitive(PType.I32, false);
    private static final DType.Primitive I64 = new DType.Primitive(PType.I64, false);
    private static final DType.Primitive U8 = new DType.Primitive(PType.U8, false);

    @Nested
    class Date {

        @Test
        void identity() {
            // Given / When / Then — singleton + canonical id; pattern-match keys depend on it
            assertThat(DateExtension.INSTANCE.extensionId()).isSameAs(ExtensionId.VORTEX_DATE);
        }

        @Test
        void dtype_isI32StorageNoMetadata() {
            // Given / When — Arrow's canonical Date type carries no metadata; days fit in I32 through year 5_881_580
            DType.Extension dtype = DateExtension.INSTANCE.dtype(false);

            // Then
            assertThat(dtype.extensionId()).isEqualTo("vortex.date");
            assertThat(dtype.storageDType()).isEqualTo(new DType.Primitive(PType.I32, false));
            assertThat(dtype.metadata()).isNull();
        }

        @Test
        void decode_tpchSample() {
            // Given — anchor against known TPC-H value 9538 = 1996-02-12
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(4);
                buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 9538);
                IntArray storage = new IntArray(I32, 1, buf);

                // When / Then
                assertThat(DateExtension.INSTANCE.decode(storage, 0))
                        .isEqualTo(LocalDate.of(1996, 2, 12));
            }
        }

        @Test
        void decode_negativeDays_returnsPreEpoch() {
            // Given — defensive: signed storage, pre-1970 must work
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(4);
                buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, -1);
                IntArray storage = new IntArray(I32, 1, buf);

                // When / Then
                assertThat(DateExtension.INSTANCE.decode(storage, 0))
                        .isEqualTo(LocalDate.of(1969, 12, 31));
            }
        }

        @Test
        void encode_singleDate_returnsEpochDay() {
            // Given / When / Then — 1996-02-12 anchored against the same TPC-H sample as decode
            assertThat(DateExtension.INSTANCE.encode(LocalDate.of(1996, 2, 12))).isEqualTo(9538);
        }

        @Test
        void encodeAll_thenDecodeAll_roundTrips() {
            // Given — a small set covers ordering + a pre-epoch row to exercise sign handling
            java.util.List<LocalDate> dates = java.util.List.of(
                    LocalDate.of(1969, 12, 31),
                    LocalDate.of(1970, 1, 1),
                    LocalDate.of(1996, 2, 12),
                    LocalDate.of(2026, 6, 9));

            // When — encode -> storage Array -> decodeAll
            int[] packed = DateExtension.INSTANCE.encodeAll(dates);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(packed.length * 4L);
                for (int i = 0; i < packed.length; i++) {
                    buf.set(ValueLayout.JAVA_INT_UNALIGNED, i * 4L, packed[i]);
                }
                IntArray storage = new IntArray(I32, packed.length, buf);

                // Then — order + values preserved end-to-end
                assertThat(DateExtension.INSTANCE.decodeAll(storage)).isEqualTo(dates);
            }
        }
    }

    @Nested
    class Time {

        @Test
        void identity() {
            assertThat(TimeExtension.INSTANCE.extensionId()).isSameAs(ExtensionId.VORTEX_TIME);
        }

        @Test
        void dtype_defaultsToMillisecondsI32() {
            // Given — default factory must pick the most common resolution
            DType.Extension dtype = TimeExtension.INSTANCE.dtype(true);

            // Then — storage width is I32 for s/ms, metadata byte 0 = TimeUnit.Milliseconds.ordinal()
            assertThat(dtype.storageDType()).isEqualTo(new DType.Primitive(PType.I32, true));
            assertThat(dtype.metadata().get(0)).isEqualTo((byte) TimeUnit.Milliseconds.ordinal());
        }

        @Test
        void dtype_nanosecondsForceI64() {
            // Given — nanos overflow I32; the factory must promote storage to I64
            DType.Extension dtype = TimeExtension.INSTANCE.dtype(TimeUnit.Nanoseconds, false);

            // Then
            assertThat(dtype.storageDType()).isEqualTo(new DType.Primitive(PType.I64, false));
            assertThat(dtype.metadata().get(0)).isEqualTo((byte) TimeUnit.Nanoseconds.ordinal());
        }

        @Test
        void decode_eachUnit() {
            // Given — round-trip a known time-of-day through every TimeUnit
            try (Arena arena = Arena.ofConfined()) {
                // Seconds: 3661 s = 01:01:01
                assertThat(TimeExtension.INSTANCE.decode(ext("vortex.time", I32, unitByte((byte) 3)),
                        i32(arena, 3661), 0))
                        .isEqualTo(LocalTime.of(1, 1, 1));
                // Milliseconds: 3_661_500 = 01:01:01.500
                assertThat(TimeExtension.INSTANCE.decode(ext("vortex.time", I32, unitByte((byte) 2)),
                        i32(arena, 3_661_500), 0))
                        .isEqualTo(LocalTime.of(1, 1, 1, 500_000_000));
                // Microseconds: 1_000_001 = 00:00:01.000001
                assertThat(TimeExtension.INSTANCE.decode(ext("vortex.time", I64, unitByte((byte) 1)),
                        i64(arena, 1_000_001L), 0))
                        .isEqualTo(LocalTime.of(0, 0, 1, 1_000));
                // Nanoseconds: 42 ns past midnight
                assertThat(TimeExtension.INSTANCE.decode(ext("vortex.time", I64, unitByte((byte) 0)),
                        i64(arena, 42L), 0))
                        .isEqualTo(LocalTime.ofNanoOfDay(42));
            }
        }

        @Test
        void decode_daysUnitThrows() {
            // Given — Days isn't a sub-second unit
            try (Arena arena = Arena.ofConfined()) {
                DType.Extension ext = ext("vortex.time", I32, unitByte((byte) 4));

                // When / Then
                assertThatThrownBy(() -> TimeExtension.INSTANCE.decode(ext, i32(arena, 0), 0))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("Days unit not valid");
            }
        }

        @Test
        void encodeAll_secondsReturnsIntArray() {
            // Given — Seconds resolution uses I32 storage; encodeAll must produce int[]
            // to match the writer's array-type switch
            Object packed = TimeExtension.INSTANCE.encodeAll(
                    java.util.List.of(LocalTime.of(1, 1, 1)), TimeUnit.Seconds);

            // Then — 01:01:01 = 3661 s
            assertThat(packed).isEqualTo(new int[]{3661});
        }

        @Test
        void encodeAll_nanosecondsReturnsLongArray() {
            // Given — nanos resolution needs I64; encodeAll switches return type to long[]
            Object packed = TimeExtension.INSTANCE.encodeAll(
                    java.util.List.of(LocalTime.ofNanoOfDay(42)), TimeUnit.Nanoseconds);

            // Then
            assertThat(packed).isEqualTo(new long[]{42L});
        }

        @Test
        void encodeAll_daysUnitThrows() {
            // Given — Days isn't sub-second; encodeAll fails with the same error decode does
            assertThatThrownBy(() -> TimeExtension.INSTANCE.encodeAll(
                    java.util.List.of(LocalTime.NOON), TimeUnit.Days))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Days unit not valid");
        }
    }

    @Nested
    class Timestamp {

        @Test
        void identity() {
            assertThat(TimestampExtension.INSTANCE.extensionId()).isSameAs(ExtensionId.VORTEX_TIMESTAMP);
        }

        @Test
        void dtype_defaultIsMsUtcless() {
            // Given — default factory yields ms storage with empty tz_len
            DType.Extension dtype = TimestampExtension.INSTANCE.dtype(false);

            // Then — byte 0 = ms ordinal, bytes 1..3 = 0 (tz_len = 0)
            assertThat(dtype.storageDType()).isEqualTo(new DType.Primitive(PType.I64, false));
            assertThat(dtype.metadata().get(0)).isEqualTo((byte) TimeUnit.Milliseconds.ordinal());
            assertThat(dtype.metadata().getShort(1)).isEqualTo((short) 0);
        }

        @Test
        void dtype_withTimezoneEncodesIanaName() {
            // Given — timezone bytes are appended after the 3-byte header so decode can pull them back
            DType.Extension dtype = TimestampExtension.INSTANCE.dtype(
                    TimeUnit.Microseconds, ZoneId.of("Europe/Paris"), false);

            // Then — header tz_len matches the UTF-8 length; the actual bytes follow
            int tzLen = Short.toUnsignedInt(dtype.metadata().getShort(1));
            assertThat(tzLen).isEqualTo("Europe/Paris".getBytes().length);
            assertThat(TimestampExtension.INSTANCE.timezone(dtype))
                    .contains(ZoneId.of("Europe/Paris"));
        }

        @Test
        void dtype_noTimezoneReadsBackAsEmpty() {
            // Given — defensive: round-trip the null-tz case through timezone()
            DType.Extension dtype = TimestampExtension.INSTANCE.dtype(
                    TimeUnit.Milliseconds, null, false);

            // Then — must not fall back to a synthetic zone
            assertThat(TimestampExtension.INSTANCE.timezone(dtype)).isEmpty();
        }

        @Test
        void dtype_utcRoundTrips() {
            // Given — UTC is a degenerate case worth checking separately from Europe/Paris
            DType.Extension dtype = TimestampExtension.INSTANCE.dtype(
                    TimeUnit.Seconds, ZoneOffset.UTC, false);

            // Then
            assertThat(TimestampExtension.INSTANCE.timezone(dtype)).contains(ZoneOffset.UTC);
        }

        @Test
        void instant_microsecondsPath_handlesNegativeRaw() {
            // Given — pre-epoch micros exercise the floorDiv / floorMod path
            long micros = -1_500_001L; // -1.500001s
            try (Arena arena = Arena.ofConfined()) {
                DType.Extension ext = ext("vortex.timestamp", I64, tzMeta((byte) 1, null));

                // When
                Instant got = TimestampExtension.INSTANCE.instant(ext, i64(arena, micros), 0);

                // Then
                assertThat(got.getEpochSecond()).isEqualTo(-2L);
                assertThat(got.getNano()).isEqualTo(499_999_000);
            }
        }

        @Test
        void zonedDateTime_withTimezone_appliesIt() {
            // Given — ms since epoch + Europe/Paris tz in metadata
            try (Arena arena = Arena.ofConfined()) {
                DType.Extension ext = ext("vortex.timestamp", I64, tzMeta((byte) 2, "Europe/Paris"));

                // When
                ZonedDateTime got = TimestampExtension.INSTANCE.zonedDateTime(ext, i64(arena, 1_000L), 0);

                // Then
                assertThat(got.getZone()).isEqualTo(ZoneId.of("Europe/Paris"));
                assertThat(got.toInstant()).isEqualTo(Instant.ofEpochMilli(1_000L));
            }
        }

        @Test
        void zonedDateTime_noTimezone_defaultsToUtc() {
            // Given — tz_len = 0 should fall back to UTC for unambiguity
            try (Arena arena = Arena.ofConfined()) {
                DType.Extension ext = ext("vortex.timestamp", I64, tzMeta((byte) 2, null));

                // When
                ZonedDateTime got = TimestampExtension.INSTANCE.zonedDateTime(ext, i64(arena, 0L), 0);

                // Then
                assertThat(got.getZone()).isEqualTo(ZoneOffset.UTC);
            }
        }

        @Test
        void encodeAll_milliseconds_thenDecodeAll_roundTrips() {
            // Given — instants chosen to exercise positive + negative + sub-second
            java.util.List<Instant> instants = java.util.List.of(
                    Instant.ofEpochMilli(-1500L),
                    Instant.ofEpochMilli(0L),
                    Instant.ofEpochMilli(1_000L),
                    Instant.ofEpochMilli(1_733_000_000_000L));

            // When
            long[] packed = TimestampExtension.INSTANCE.encodeAll(instants, TimeUnit.Milliseconds);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(packed.length * 8L);
                for (int i = 0; i < packed.length; i++) {
                    buf.set(ValueLayout.JAVA_LONG_UNALIGNED, i * 8L, packed[i]);
                }
                LongArray storage = new LongArray(I64, packed.length, buf);
                DType.Extension ext = ext("vortex.timestamp", I64, tzMeta((byte) 2, null));

                // Then — order + values preserved end-to-end
                assertThat(TimestampExtension.INSTANCE.decodeAll(ext, storage)).isEqualTo(instants);
            }
        }

        @Test
        void encode_daysUnitThrows() {
            // Given — Days isn't a sub-second unit on the encode side either
            assertThatThrownBy(() -> TimestampExtension.INSTANCE.encode(
                    Instant.EPOCH, TimeUnit.Days))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Days unit not valid");
        }

        @Test
        void timezone_truncatedMetadata_throws() {
            // Given — declared tz_len longer than buffer can carry
            ByteBuffer meta = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
            meta.put(0, (byte) 2);
            meta.putShort(1, (short) 5);
            meta.put(3, (byte) 'U');
            meta.put(4, (byte) 'T');
            meta.put(5, (byte) 'C');
            DType.Extension truncated = ext("vortex.timestamp", I64, meta);

            // When / Then
            assertThatThrownBy(() -> TimestampExtension.INSTANCE.timezone(truncated))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("truncated");
        }
    }

    @Nested
    class Uuid {

        @Test
        void identity() {
            assertThat(UuidExtension.INSTANCE.extensionId()).isSameAs(ExtensionId.VORTEX_UUID);
        }

        @Test
        void dtype_isFixedSizeListOf16U8() {
            // Given / When — UUID storage is canonically FixedSizeList<U8>(16); no extension metadata
            DType.Extension dtype = UuidExtension.INSTANCE.dtype(true);

            // Then
            DType.Primitive u8 = new DType.Primitive(PType.U8, false);
            assertThat(dtype.storageDType()).isEqualTo(new DType.FixedSizeList(u8, 16, true));
            assertThat(dtype.metadata()).isNull();
        }

        @Test
        void decode_roundTripsKnownValue() {
            // Given — RFC 9562 example
            java.util.UUID expected = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(16);
                long msb = expected.getMostSignificantBits();
                long lsb = expected.getLeastSignificantBits();
                for (int k = 0; k < 8; k++) {
                    buf.set(ValueLayout.JAVA_BYTE, k, (byte) ((msb >> (56 - 8 * k)) & 0xff));
                    buf.set(ValueLayout.JAVA_BYTE, 8 + k, (byte) ((lsb >> (56 - 8 * k)) & 0xff));
                }
                ByteArray inner = new ByteArray(U8, 16, buf);
                FixedSizeListArray storage = new FixedSizeListArray(
                        new DType.FixedSizeList(U8, 16, false), 1, inner);

                // When / Then
                assertThat(UuidExtension.INSTANCE.decode(storage, 0)).isEqualTo(expected);
            }
        }

        @Test
        void decode_allOnes_noSignExtension() {
            // Given — 0xff in every byte trips sign-extension bugs in the mask
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(16);
                for (int k = 0; k < 16; k++) {
                    buf.set(ValueLayout.JAVA_BYTE, k, (byte) 0xff);
                }
                ByteArray inner = new ByteArray(U8, 16, buf);
                FixedSizeListArray storage = new FixedSizeListArray(
                        new DType.FixedSizeList(U8, 16, false), 1, inner);

                // When / Then
                assertThat(UuidExtension.INSTANCE.decode(storage, 0))
                        .isEqualTo(new java.util.UUID(-1L, -1L));
            }
        }

        @Test
        void encode_thenDecodeAll_roundTrips() {
            // Given — pair of UUIDs covers both halves of the packed buffer + sign-extension edge
            java.util.List<UUID> ids = java.util.List.of(
                    UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                    new UUID(-1L, -1L));

            // When — encodeAll produces a flat byte[] sized 16*N
            byte[] packed = UuidExtension.INSTANCE.encodeAll(ids);
            assertThat(packed).hasSize(32);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(packed.length);
                for (int i = 0; i < packed.length; i++) {
                    buf.set(ValueLayout.JAVA_BYTE, i, packed[i]);
                }
                ByteArray inner = new ByteArray(U8, packed.length, buf);
                FixedSizeListArray storage = new FixedSizeListArray(
                        new DType.FixedSizeList(U8, 16, false), ids.size(), inner);

                // Then
                assertThat(UuidExtension.INSTANCE.decodeAll(storage)).isEqualTo(ids);
            }
        }

        @Test
        void decode_wrongFixedSize_throws() {
            // Given — 8 != 16; reject up front
            try (Arena arena = Arena.ofConfined()) {
                ByteArray inner = new ByteArray(U8, 8, arena.allocate(8));
                FixedSizeListArray storage = new FixedSizeListArray(
                        new DType.FixedSizeList(U8, 8, false), 1, inner);

                // When / Then
                assertThatThrownBy(() -> UuidExtension.INSTANCE.decode(storage, 0))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("fixedSize 16");
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static DType.Extension ext(String id, DType storage, ByteBuffer meta) {
        return new DType.Extension(id, storage, meta, false);
    }

    private static ByteBuffer unitByte(byte tag) {
        ByteBuffer meta = ByteBuffer.allocate(1);
        meta.put(0, tag);
        return meta;
    }

    private static ByteBuffer tzMeta(byte unitTag, String tz) {
        byte[] tzBytes = tz == null ? new byte[0] : tz.getBytes(StandardCharsets.UTF_8);
        ByteBuffer meta = ByteBuffer.allocate(3 + tzBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        meta.put(0, unitTag);
        meta.putShort(1, (short) tzBytes.length);
        for (int k = 0; k < tzBytes.length; k++) {
            meta.put(3 + k, tzBytes[k]);
        }
        return meta;
    }

    private static IntArray i32(Arena arena, int value) {
        MemorySegment buf = arena.allocate(4);
        buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, value);
        return new IntArray(I32, 1, buf);
    }

    private static LongArray i64(Arena arena, long value) {
        MemorySegment buf = arena.allocate(8);
        buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, value);
        return new LongArray(I64, 1, buf);
    }
}
