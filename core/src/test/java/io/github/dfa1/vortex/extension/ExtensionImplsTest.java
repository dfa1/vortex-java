package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionImplsTest {

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
    }
}
