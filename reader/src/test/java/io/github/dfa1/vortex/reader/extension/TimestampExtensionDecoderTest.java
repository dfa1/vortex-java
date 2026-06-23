package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static io.github.dfa1.vortex.reader.extension.ExtensionTestSupport.I64;
import static io.github.dfa1.vortex.reader.extension.ExtensionTestSupport.ext;
import static io.github.dfa1.vortex.reader.extension.ExtensionTestSupport.i64;
import static io.github.dfa1.vortex.reader.extension.ExtensionTestSupport.tzMeta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimestampExtensionDecoderTest {

    private final TimestampExtensionDecoder sut = TimestampExtensionDecoder.INSTANCE;

    @Test
    void identity() {
        assertThat(sut.extensionId()).isSameAs(ExtensionId.VORTEX_TIMESTAMP);
    }

    @Test
    void dtype_defaultIsMsUtcless() {
        // Given — default factory yields ms storage with empty tz_len
        DType.Extension dtype = sut.dtype(false);

        // Then — byte 0 = ms ordinal, bytes 1..3 = 0 (tz_len = 0)
        assertThat(dtype.storageDType()).isEqualTo(DType.I64);
        assertThat(dtype.metadata().get(0)).isEqualTo((byte) TimeUnit.Milliseconds.ordinal());
        assertThat(dtype.metadata().getShort(1)).isEqualTo((short) 0);
    }

    @Test
    void dtype_withTimezoneEncodesIanaName() {
        // Given — timezone bytes are appended after the 3-byte header so decode can pull them back
        DType.Extension dtype = sut.dtype(TimeUnit.Microseconds, ZoneId.of("Europe/Paris"), false);

        // Then — header tz_len matches the UTF-8 length; the actual bytes follow
        int tzLen = Short.toUnsignedInt(dtype.metadata().getShort(1));
        assertThat(tzLen).isEqualTo("Europe/Paris".getBytes().length);
        assertThat(sut.timezone(dtype)).contains(ZoneId.of("Europe/Paris"));
    }

    @Test
    void dtype_noTimezoneReadsBackAsEmpty() {
        // Given — defensive: round-trip the null-tz case through timezone()
        DType.Extension dtype = sut.dtype(TimeUnit.Milliseconds, null, false);

        // Then — must not fall back to a synthetic zone
        assertThat(sut.timezone(dtype)).isEmpty();
    }

    @Test
    void dtype_utcRoundTrips() {
        // Given — UTC is a degenerate case worth checking separately from Europe/Paris
        DType.Extension dtype = sut.dtype(TimeUnit.Seconds, ZoneOffset.UTC, false);

        // Then
        assertThat(sut.timezone(dtype)).contains(ZoneOffset.UTC);
    }

    @Test
    void instant_microsecondsPath_handlesNegativeRaw() {
        // Given — pre-epoch micros exercise the floorDiv / floorMod path
        long micros = -1_500_001L; // -1.500001s
        try (Arena arena = Arena.ofConfined()) {
            DType.Extension dtype = ext("vortex.timestamp", I64, tzMeta((byte) 1, null));

            // When
            Instant got = sut.instant(dtype, i64(arena, micros), 0);

            // Then
            assertThat(got.getEpochSecond()).isEqualTo(-2L);
            assertThat(got.getNano()).isEqualTo(499_999_000);
        }
    }

    @Test
    void zonedDateTime_withTimezone_appliesIt() {
        // Given — ms since epoch + Europe/Paris tz in metadata
        try (Arena arena = Arena.ofConfined()) {
            DType.Extension dtype = ext("vortex.timestamp", I64, tzMeta((byte) 2, "Europe/Paris"));

            // When
            ZonedDateTime got = sut.zonedDateTime(dtype, i64(arena, 1_000L), 0);

            // Then
            assertThat(got.getZone()).isEqualTo(ZoneId.of("Europe/Paris"));
            assertThat(got.toInstant()).isEqualTo(Instant.ofEpochMilli(1_000L));
        }
    }

    @Test
    void zonedDateTime_noTimezone_defaultsToUtc() {
        // Given — tz_len = 0 should fall back to UTC for unambiguity
        try (Arena arena = Arena.ofConfined()) {
            DType.Extension dtype = ext("vortex.timestamp", I64, tzMeta((byte) 2, null));

            // When
            ZonedDateTime got = sut.zonedDateTime(dtype, i64(arena, 0L), 0);

            // Then
            assertThat(got.getZone()).isEqualTo(ZoneOffset.UTC);
        }
    }

    @Test
    void decodeAll_maskedArray_yieldsNullAtInvalidPositions() {
        // Given — Milliseconds storage with row 1 null. ext dtype uses I64 storage.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(16);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 1_000L);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 8, 0L);
            LongArray inner = new MaterializedLongArray(I64, 2, buf);
            MemorySegment validityBuf = arena.allocate(1);
            validityBuf.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b0000_0001);
            BoolArray validity = new MaterializedBoolArray(DType.BOOL, 2, validityBuf);
            DType.Extension dtype = sut.dtype(true);

            // When
            List<Instant> out = sut.decodeAll(dtype, new MaskedArray(inner, validity));

            // Then
            assertThat(out).containsExactly(Instant.ofEpochMilli(1_000L), null);
        }
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
        assertThatThrownBy(() -> sut.timezone(truncated))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("truncated");
    }
}
