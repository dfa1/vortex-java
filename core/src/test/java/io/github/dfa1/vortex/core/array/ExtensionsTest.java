package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionsTest {

    private static final DType.Primitive I32 = new DType.Primitive(PType.I32, false);
    private static final DType DATE_DTYPE = new DType.Extension(Extensions.DATE, I32, null, false);

    @Test
    void localDate_zeroIsUnixEpoch() {
        // Given — Arrow-compatible: 0 == 1970-01-01
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
            IntArray sut = new IntArray(DATE_DTYPE, 1, buf);

            // When / Then
            assertThat(Extensions.localDate(sut, 0)).isEqualTo(LocalDate.of(1970, 1, 1));
        }
    }

    @Test
    void localDate_tpchSampleValue_matchesExpected() {
        // Given — anchor against a known TPC-H value: 9538 = 1996-02-12.
        // Catches accidental epoch-shift regressions (e.g. days-since-2000).
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 9538);
            IntArray sut = new IntArray(DATE_DTYPE, 1, buf);

            // When / Then
            assertThat(Extensions.localDate(sut, 0)).isEqualTo(LocalDate.of(1996, 2, 12));
        }
    }

    @Test
    void localDate_negativeDays_returnsPreEpoch() {
        // Given — defensive: integer storage is signed, so pre-1970 dates must work
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, -1);
            IntArray sut = new IntArray(DATE_DTYPE, 1, buf);

            // When / Then
            assertThat(Extensions.localDate(sut, 0)).isEqualTo(LocalDate.of(1969, 12, 31));
        }
    }

    @Test
    void localDate_nonDateDtype_throws() {
        // Given — guards against silent misinterpretation (e.g. plain I32 as days)
        try (Arena arena = Arena.ofConfined()) {
            IntArray sut = new IntArray(I32, 1, arena.allocate(4));

            // When / Then
            assertThatThrownBy(() -> Extensions.localDate(sut, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("non-date");
        }
    }

    @Test
    void localDate_withExplicitExtAndStorage_decodes() {
        // Given — ExtEncoding.decode strips the extension wrapper before the
        // TUI gets the array, so the caller threads the declared dtype back
        // in. This overload must still verify the extension id rather than
        // trust any caller-supplied storage as a date.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 9538);
            IntArray storage = new IntArray(I32, 1, buf);
            DType.Extension ext = new DType.Extension(Extensions.DATE, I32, null, false);

            // When / Then
            assertThat(Extensions.localDate(ext, storage, 0))
                    .isEqualTo(LocalDate.of(1996, 2, 12));
        }
    }

    @Test
    void localDate_withWrongExtensionId_throws() {
        // Given — passing some other extension's storage array must not be
        // silently interpreted as a date
        try (Arena arena = Arena.ofConfined()) {
            IntArray storage = new IntArray(I32, 1, arena.allocate(4));
            DType.Extension notDate = new DType.Extension("vortex.something", I32, null, false);

            // When / Then
            assertThatThrownBy(() -> Extensions.localDate(notDate, storage, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("non-date extension");
        }
    }

    @Test
    void localTime_secondsUnit_decodesViaI32() {
        // Given — TimeUnit tag 3 (Seconds), storage I32: 3661 seconds = 01:01:01
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 3661);
            IntArray storage = new IntArray(I32, 1, buf);

            // When / Then
            assertThat(Extensions.localTime(timeExt((byte) 3), storage, 0))
                    .isEqualTo(java.time.LocalTime.of(1, 1, 1));
        }
    }

    @Test
    void localTime_millisecondsUnit_decodesViaI32() {
        // Given — TimeUnit tag 2 (Milliseconds): 3_661_500 ms = 01:01:01.500
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 3_661_500);
            IntArray storage = new IntArray(I32, 1, buf);

            // When / Then
            assertThat(Extensions.localTime(timeExt((byte) 2), storage, 0))
                    .isEqualTo(java.time.LocalTime.of(1, 1, 1, 500_000_000));
        }
    }

    @Test
    void localTime_microsecondsUnit_decodesViaI64() {
        // Given — TimeUnit tag 1 (Microseconds): 1 second + 1 microsecond
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(8);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 1_000_001L);
            LongArray storage = new LongArray(new DType.Primitive(PType.I64, false), 1, buf);

            // When / Then
            assertThat(Extensions.localTime(timeExt((byte) 1), storage, 0))
                    .isEqualTo(java.time.LocalTime.of(0, 0, 1, 1_000));
        }
    }

    @Test
    void localTime_nanosecondsUnit_decodesViaI64() {
        // Given — TimeUnit tag 0 (Nanoseconds): 42 nanos past midnight
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(8);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 42L);
            LongArray storage = new LongArray(new DType.Primitive(PType.I64, false), 1, buf);

            // When / Then
            assertThat(Extensions.localTime(timeExt((byte) 0), storage, 0))
                    .isEqualTo(java.time.LocalTime.ofNanoOfDay(42));
        }
    }

    @Test
    void localTime_daysUnit_throws() {
        // Given — Days isn't a sub-second unit, so vortex.time with Days is malformed
        try (Arena arena = Arena.ofConfined()) {
            IntArray storage = new IntArray(I32, 1, arena.allocate(4));

            // When / Then
            assertThatThrownBy(() -> Extensions.localTime(timeExt((byte) 4), storage, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Days unit not valid");
        }
    }

    @Test
    void localTime_wrongExtensionId_throws() {
        // Given — guards against calling with a non-time extension
        try (Arena arena = Arena.ofConfined()) {
            IntArray storage = new IntArray(I32, 1, arena.allocate(4));
            DType.Extension wrongExt = new DType.Extension("vortex.date", I32, null, false);

            // When / Then
            assertThatThrownBy(() -> Extensions.localTime(wrongExt, storage, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("non-time extension");
        }
    }

    @Test
    void localTime_missingMetadata_throws() {
        // Given — metadata byte must specify TimeUnit; otherwise we can't know
        // whether the storage is in seconds, ms, μs, or ns
        try (Arena arena = Arena.ofConfined()) {
            IntArray storage = new IntArray(I32, 1, arena.allocate(4));
            DType.Extension noMetaExt = new DType.Extension(Extensions.TIME, I32, null, false);

            // When / Then
            assertThatThrownBy(() -> Extensions.localTime(noMetaExt, storage, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing TimeUnit metadata");
        }
    }

    private static DType.Extension timeExt(byte tag) {
        java.nio.ByteBuffer meta = java.nio.ByteBuffer.allocate(1);
        meta.put(0, tag);
        return new DType.Extension(Extensions.TIME, I32, meta, false);
    }

    @Test
    void instant_secondsUnit_decodesEpoch() {
        // Given — TimeUnit tag 3 (Seconds), no tz; raw 0 = Unix epoch
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(8);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 0L);
            LongArray storage = new LongArray(new DType.Primitive(PType.I64, false), 1, buf);

            // When / Then
            assertThat(Extensions.instant(timestampExt((byte) 3, null), storage, 0))
                    .isEqualTo(java.time.Instant.EPOCH);
        }
    }

    @Test
    void instant_microsecondsUnit_handlesNegativeRaw() {
        // Given — pre-epoch micros: 1996-02-12T00:00:00Z is 824083200_000_000 micros
        // Negate to flip into pre-epoch so the floorDiv path is actually exercised
        // (plain / would round the 2-micro remainder towards zero and skew seconds)
        long micros = -1_500_001L; // -1.500001s; expected: epochSecond = -2, nanos = 499_999_000
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(8);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, micros);
            LongArray storage = new LongArray(new DType.Primitive(PType.I64, false), 1, buf);

            // When
            java.time.Instant got = Extensions.instant(timestampExt((byte) 1, null), storage, 0);

            // Then
            assertThat(got.getEpochSecond()).isEqualTo(-2L);
            assertThat(got.getNano()).isEqualTo(499_999_000);
        }
    }

    @Test
    void instant_nanosecondsUnit_decodesFullPrecision() {
        // Given — TimeUnit tag 0 (Nanoseconds): 1_000_000_001 ns = 1.000_000_001 s
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(8);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 1_000_000_001L);
            LongArray storage = new LongArray(new DType.Primitive(PType.I64, false), 1, buf);

            // When / Then
            assertThat(Extensions.instant(timestampExt((byte) 0, null), storage, 0))
                    .isEqualTo(java.time.Instant.ofEpochSecond(1, 1));
        }
    }

    @Test
    void instant_daysUnit_throws() {
        // Given — Days isn't valid for timestamps
        try (Arena arena = Arena.ofConfined()) {
            LongArray storage = new LongArray(new DType.Primitive(PType.I64, false), 1, arena.allocate(8));

            // When / Then
            assertThatThrownBy(() -> Extensions.instant(timestampExt((byte) 4, null), storage, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Days unit not valid");
        }
    }

    @Test
    void zonedDateTime_withTimezone_appliesIt() {
        // Given — milliseconds since epoch + a Europe/Paris tz string in metadata
        long ms = 1_000L; // 1 second past epoch
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(8);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, ms);
            LongArray storage = new LongArray(new DType.Primitive(PType.I64, false), 1, buf);

            // When
            java.time.ZonedDateTime got = Extensions.zonedDateTime(
                    timestampExt((byte) 2, "Europe/Paris"), storage, 0);

            // Then
            assertThat(got.getZone()).isEqualTo(java.time.ZoneId.of("Europe/Paris"));
            assertThat(got.toInstant()).isEqualTo(java.time.Instant.ofEpochMilli(ms));
        }
    }

    @Test
    void zonedDateTime_noTimezone_defaultsToUtc() {
        // Given — tz_len = 0 in metadata means caller didn't record a zone; default UTC
        // is unambiguous and matches the Arrow convention
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(8);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 0L);
            LongArray storage = new LongArray(new DType.Primitive(PType.I64, false), 1, buf);

            // When
            java.time.ZonedDateTime got = Extensions.zonedDateTime(
                    timestampExt((byte) 2, null), storage, 0);

            // Then
            assertThat(got.getZone()).isEqualTo(java.time.ZoneOffset.UTC);
        }
    }

    @Test
    void timezone_truncatedMetadata_throws() {
        // Given — metadata claims tz_len=5 but provides only 3 bytes of payload
        java.nio.ByteBuffer meta = java.nio.ByteBuffer.allocate(6).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        meta.put(0, (byte) 2);     // ms
        meta.putShort(1, (short) 5); // tz_len=5
        meta.put(3, (byte) 'U');
        meta.put(4, (byte) 'T');
        meta.put(5, (byte) 'C');   // only 3 of the 5 declared bytes present
        DType.Extension ext = new DType.Extension(Extensions.TIMESTAMP,
                new DType.Primitive(PType.I64, false), meta, false);

        // When / Then
        assertThatThrownBy(() -> Extensions.timezone(ext))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("truncated");
    }

    private static DType.Extension timestampExt(byte tag, String tz) {
        byte[] tzBytes = tz == null ? new byte[0] : tz.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.ByteBuffer meta = java.nio.ByteBuffer.allocate(3 + tzBytes.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        meta.put(0, tag);
        meta.putShort(1, (short) tzBytes.length);
        for (int k = 0; k < tzBytes.length; k++) {
            meta.put(3 + k, tzBytes[k]);
        }
        return new DType.Extension(Extensions.TIMESTAMP,
                new DType.Primitive(PType.I64, false), meta, false);
    }

    @Test
    void uuid_roundTripsKnownValue() {
        // Given — Arrow canonical layout: FixedSizeList<U8>[16]; one well-known UUID
        // (RFC 9562 example) plus its inverse, so msb/lsb extraction is exercised in
        // both halves rather than only the high bytes.
        java.util.UUID expected = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(16);
            long msb = expected.getMostSignificantBits();
            long lsb = expected.getLeastSignificantBits();
            for (int k = 0; k < 8; k++) {
                buf.set(ValueLayout.JAVA_BYTE, k, (byte) ((msb >> (56 - 8 * k)) & 0xff));
                buf.set(ValueLayout.JAVA_BYTE, 8 + k, (byte) ((lsb >> (56 - 8 * k)) & 0xff));
            }
            ByteArray inner = new ByteArray(new DType.Primitive(PType.U8, false), 16, buf);
            DType.FixedSizeList fslDtype = new DType.FixedSizeList(
                    new DType.Primitive(PType.U8, false), 16, false);
            FixedSizeListArray sut = new FixedSizeListArray(fslDtype, 1, inner);

            // When / Then
            assertThat(Extensions.uuid(sut, 0)).isEqualTo(expected);
        }
    }

    @Test
    void uuid_zeroBytes_decodesToZeroUuid() {
        // Given — defensive: all-zero UUID is the most common "null UUID" sentinel
        // and a regression test for sign extension on getByte
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(16);
            ByteArray inner = new ByteArray(new DType.Primitive(PType.U8, false), 16, buf);
            DType.FixedSizeList fslDtype = new DType.FixedSizeList(
                    new DType.Primitive(PType.U8, false), 16, false);
            FixedSizeListArray sut = new FixedSizeListArray(fslDtype, 1, inner);

            // When / Then
            assertThat(Extensions.uuid(sut, 0))
                    .isEqualTo(new java.util.UUID(0L, 0L));
        }
    }

    @Test
    void uuid_allOnesBytes_decodesWithoutSignExtension() {
        // Given — 0xff in every position; if getByte returned a sign-extended int
        // and we forgot the & 0xffL mask, msb/lsb would land as 0xff..fff..ff with
        // sign bits poisoning the upper longs. Use the highest-bit pattern as the
        // sign-extension trap.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(16);
            for (int k = 0; k < 16; k++) {
                buf.set(ValueLayout.JAVA_BYTE, k, (byte) 0xff);
            }
            ByteArray inner = new ByteArray(new DType.Primitive(PType.U8, false), 16, buf);
            DType.FixedSizeList fslDtype = new DType.FixedSizeList(
                    new DType.Primitive(PType.U8, false), 16, false);
            FixedSizeListArray sut = new FixedSizeListArray(fslDtype, 1, inner);

            // When / Then
            assertThat(Extensions.uuid(sut, 0))
                    .isEqualTo(new java.util.UUID(-1L, -1L));
        }
    }

    @Test
    void uuid_wrongFixedSize_throws() {
        // Given — 8-byte FixedSizeList isn't a UUID; catch the mismatch up front
        try (Arena arena = Arena.ofConfined()) {
            ByteArray inner = new ByteArray(new DType.Primitive(PType.U8, false), 8, arena.allocate(8));
            DType.FixedSizeList wrongSize = new DType.FixedSizeList(
                    new DType.Primitive(PType.U8, false), 8, false);
            FixedSizeListArray sut = new FixedSizeListArray(wrongSize, 1, inner);

            // When / Then
            assertThatThrownBy(() -> Extensions.uuid(sut, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("fixedSize 16");
        }
    }

    @Test
    void uuid_wrongStorageType_throws() {
        // Given — a plain IntArray isn't FixedSizeList; guard against callers
        // passing the wrong column by mistake
        try (Arena arena = Arena.ofConfined()) {
            IntArray notFsl = new IntArray(I32, 1, arena.allocate(4));

            // When / Then
            assertThatThrownBy(() -> Extensions.uuid(notFsl, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("FixedSizeListArray");
        }
    }

    @Test
    void uuid_explicitExtensionOverload_verifiesId() {
        // Given — passing a non-uuid extension dtype must not be silently
        // reinterpreted as a uuid storage column
        try (Arena arena = Arena.ofConfined()) {
            ByteArray inner = new ByteArray(new DType.Primitive(PType.U8, false), 16, arena.allocate(16));
            DType.FixedSizeList fslDtype = new DType.FixedSizeList(
                    new DType.Primitive(PType.U8, false), 16, false);
            FixedSizeListArray storage = new FixedSizeListArray(fslDtype, 1, inner);
            DType.Extension wrongExt = new DType.Extension("vortex.something", fslDtype, null, false);

            // When / Then
            assertThatThrownBy(() -> Extensions.uuid(wrongExt, storage, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("non-uuid extension");
        }
    }

    @Test
    void localDate_indexOutOfBounds_throws() {
        // Given — both overloads must reject indices past the array length
        // rather than silently reading whatever the storage decoder returns
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            IntArray storage = new IntArray(I32, 1, buf);
            DType.Extension ext = new DType.Extension(Extensions.DATE, I32, null, false);
            IntArray dated = new IntArray(DATE_DTYPE, 1, buf);

            // When / Then
            assertThatThrownBy(() -> Extensions.localDate(dated, 1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> Extensions.localDate(dated, -1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> Extensions.localDate(ext, storage, 1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Test
    void localDate_unsupportedStorage_throws() {
        // Given — a date dtype on top of a varbin array makes no semantic sense
        try (Arena arena = Arena.ofConfined()) {
            VarBinArray badStorage = new VarBinArray(DATE_DTYPE, 1,
                    arena.allocate(0), arena.allocate(8), PType.I32);

            // When / Then
            assertThatThrownBy(() -> Extensions.localDate(badStorage, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported storage");
        }
    }
}
