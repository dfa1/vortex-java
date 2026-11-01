package io.github.dfa1.vortex.reader.extension;


import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

        // Then — milliseconds, no timezone
        assertThat(dtype.storageDType()).isEqualTo(DTypes.I64);
        assertThat(dtype.metadata()).isEqualTo(new io.github.dfa1.vortex.core.model.TimestampMetadata(TimeUnit.Milliseconds, null));
    }

    @Test
    void dtype_withTimezoneRecordsIt() {
        // Given
        DType.Extension dtype = sut.dtype(TimeUnit.Microseconds, ZoneId.of("Europe/Paris"), false);

        // Then — the zone the decoder reads back is the one the dtype was built with
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
    void decodeAll_maskedArray_yieldsNullAtInvalidPositions() {
        // Given — Milliseconds storage with row 1 null. ext dtype uses I64 storage.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(16);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 1_000L);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 8, 0L);
            LongArray inner = new MaterializedLongArray(I64, 2, buf);
            MemorySegment validityBuf = arena.allocate(1);
            validityBuf.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b0000_0001);
            BoolArray validity = new MaterializedBoolArray(DTypes.BOOL, 2, validityBuf);
            DType.Extension dtype = sut.dtype(true);

            // When
            List<Instant> out = sut.decodeAll(dtype, new MaskedArray(inner, validity));

            // Then
            assertThat(out).containsExactly(Instant.ofEpochMilli(1_000L), null);
        }
    }

    @Test
    void truncatedMetadata_refusedWhenParsed() {
        // Given — declared tz_len longer than buffer can carry
        MemorySegment meta = MemorySegment.ofArray(new byte[]{2, 5, 0, (byte) 0x55, (byte) 0x54, (byte) 0x43});

        // When / Then — refused when the file's dtype is parsed, before any decoder reads the zone
        assertThatThrownBy(() -> ext("vortex.timestamp", I64, meta))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("truncated");
    }
}
