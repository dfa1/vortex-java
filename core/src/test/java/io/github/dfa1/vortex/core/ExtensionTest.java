package io.github.dfa1.vortex.core;

import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.FixedSizeListArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionTest {

    private static final DType.Primitive I32 = new DType.Primitive(PType.I32, false);
    private static final DType.Primitive I64 = new DType.Primitive(PType.I64, false);
    private static final DType.Primitive U8 = new DType.Primitive(PType.U8, false);

    @Test
    void of_recognisedIds_returnSingletons() {
        // Given / When / Then — known ids resolve to the cached singletons so
        // identity comparison and pattern-match cases work without per-call alloc
        assertThat(Extension.of("vortex.date")).isSameAs(Extension.DATE);
        assertThat(Extension.of("vortex.time")).isSameAs(Extension.TIME);
        assertThat(Extension.of("vortex.timestamp")).isSameAs(Extension.TIMESTAMP);
        assertThat(Extension.of("vortex.uuid")).isSameAs(Extension.UUID);
    }

    @Test
    void of_unknownId_returnsCustomWithRawString() {
        // Given — open-world fallback; the id must round-trip verbatim so callers
        // can still apply their own decoding for non-spec extensions
        Extension sut = Extension.of("acme.geopoint");

        // Then
        assertThat(sut).isInstanceOf(Extension.Custom.class);
        assertThat(sut.id()).isEqualTo("acme.geopoint");
    }

    @Test
    void kind_onDTypeExtension_dispatchesViaPatternMatch() {
        // Given — practical sealed-switch usage that motivates the redesign
        DType.Extension date = ext("vortex.date", I32, null);
        DType.Extension custom = ext("acme.thing", I32, null);

        // When / Then
        assertThat(classify(date)).isEqualTo("date");
        assertThat(classify(custom)).isEqualTo("custom:acme.thing");
    }

    private static String classify(DType.Extension ext) {
        return switch (ext.kind()) {
            case Extension.Date d -> "date";
            case Extension.Time t -> "time";
            case Extension.Timestamp ts -> "timestamp";
            case Extension.Uuid u -> "uuid";
            case Extension.Custom c -> "custom:" + c.id();
        };
    }

    @Test
    void date_decodes_tpchSample() {
        // Given — anchor against known TPC-H value 9538 = 1996-02-12
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 9538);
            IntArray storage = new IntArray(I32, 1, buf);

            // When / Then
            assertThat(Extension.DATE.decode(storage, 0)).isEqualTo(LocalDate.of(1996, 2, 12));
        }
    }

    @Test
    void date_negativeDays_returnsPreEpoch() {
        // Given — defensive: signed storage, pre-1970 must work
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, -1);
            IntArray storage = new IntArray(I32, 1, buf);

            // When / Then
            assertThat(Extension.DATE.decode(storage, 0)).isEqualTo(LocalDate.of(1969, 12, 31));
        }
    }

    @Test
    void time_eachUnit_decodesCorrectly() {
        // Given — round-trip a known time-of-day through every TimeUnit
        try (Arena arena = Arena.ofConfined()) {
            // Seconds: 3661 s = 01:01:01
            assertThat(Extension.TIME.decode(ext("vortex.time", I32, unitByte((byte) 3)),
                    i32(arena, 3661), 0))
                    .isEqualTo(LocalTime.of(1, 1, 1));
            // Milliseconds: 3_661_500 = 01:01:01.500
            assertThat(Extension.TIME.decode(ext("vortex.time", I32, unitByte((byte) 2)),
                    i32(arena, 3_661_500), 0))
                    .isEqualTo(LocalTime.of(1, 1, 1, 500_000_000));
            // Microseconds: 1_000_001 = 00:00:01.000001
            assertThat(Extension.TIME.decode(ext("vortex.time", I64, unitByte((byte) 1)),
                    i64(arena, 1_000_001L), 0))
                    .isEqualTo(LocalTime.of(0, 0, 1, 1_000));
            // Nanoseconds: 42 ns past midnight
            assertThat(Extension.TIME.decode(ext("vortex.time", I64, unitByte((byte) 0)),
                    i64(arena, 42L), 0))
                    .isEqualTo(LocalTime.ofNanoOfDay(42));
        }
    }

    @Test
    void time_daysUnit_throws() {
        // Given — Days isn't a sub-second unit
        try (Arena arena = Arena.ofConfined()) {
            DType.Extension ext = ext("vortex.time", I32, unitByte((byte) 4));

            // When / Then
            assertThatThrownBy(() -> Extension.TIME.decode(ext, i32(arena, 0), 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Days unit not valid");
        }
    }

    @Test
    void timestamp_instant_microsecondsPath_handlesNegativeRaw() {
        // Given — pre-epoch micros exercise the floorDiv / floorMod path
        long micros = -1_500_001L; // -1.500001s
        try (Arena arena = Arena.ofConfined()) {
            DType.Extension ext = ext("vortex.timestamp", I64, tzMeta((byte) 1, null));

            // When
            Instant got = Extension.TIMESTAMP.instant(ext, i64(arena, micros), 0);

            // Then
            assertThat(got.getEpochSecond()).isEqualTo(-2L);
            assertThat(got.getNano()).isEqualTo(499_999_000);
        }
    }

    @Test
    void timestamp_zonedDateTime_withTimezone_appliesIt() {
        // Given — ms since epoch + Europe/Paris tz in metadata
        try (Arena arena = Arena.ofConfined()) {
            DType.Extension ext = ext("vortex.timestamp", I64, tzMeta((byte) 2, "Europe/Paris"));

            // When
            ZonedDateTime got = Extension.TIMESTAMP.zonedDateTime(ext, i64(arena, 1_000L), 0);

            // Then
            assertThat(got.getZone()).isEqualTo(ZoneId.of("Europe/Paris"));
            assertThat(got.toInstant()).isEqualTo(Instant.ofEpochMilli(1_000L));
        }
    }

    @Test
    void timestamp_zonedDateTime_noTimezone_defaultsToUtc() {
        // Given — tz_len = 0 should fall back to UTC for unambiguity
        try (Arena arena = Arena.ofConfined()) {
            DType.Extension ext = ext("vortex.timestamp", I64, tzMeta((byte) 2, null));

            // When
            ZonedDateTime got = Extension.TIMESTAMP.zonedDateTime(ext, i64(arena, 0L), 0);

            // Then
            assertThat(got.getZone()).isEqualTo(ZoneOffset.UTC);
        }
    }

    @Test
    void timestamp_timezone_truncatedMetadata_throws() {
        // Given — declared tz_len longer than buffer can carry
        ByteBuffer meta = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        meta.put(0, (byte) 2);
        meta.putShort(1, (short) 5);
        meta.put(3, (byte) 'U');
        meta.put(4, (byte) 'T');
        meta.put(5, (byte) 'C');
        DType.Extension truncated = ext("vortex.timestamp", I64, meta);

        // When / Then
        assertThatThrownBy(() -> Extension.TIMESTAMP.timezone(truncated))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void uuid_roundTripsKnownValue() {
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
            assertThat(Extension.UUID.decode(storage, 0)).isEqualTo(expected);
        }
    }

    @Test
    void uuid_allOnes_decodesWithoutSignExtension() {
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
            assertThat(Extension.UUID.decode(storage, 0))
                    .isEqualTo(new java.util.UUID(-1L, -1L));
        }
    }

    @Test
    void uuid_wrongFixedSize_throws() {
        // Given — 8 != 16; reject up front
        try (Arena arena = Arena.ofConfined()) {
            ByteArray inner = new ByteArray(U8, 8, arena.allocate(8));
            FixedSizeListArray storage = new FixedSizeListArray(
                    new DType.FixedSizeList(U8, 8, false), 1, inner);

            // When / Then
            assertThatThrownBy(() -> Extension.UUID.decode(storage, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("fixedSize 16");
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
