package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.github.dfa1.vortex.reader.array.TestArrays.bools;
import static io.github.dfa1.vortex.reader.array.TestArrays.bytes;
import static io.github.dfa1.vortex.reader.array.TestArrays.doubles;
import static io.github.dfa1.vortex.reader.array.TestArrays.ints;
import static io.github.dfa1.vortex.reader.array.TestArrays.longs;
import static io.github.dfa1.vortex.reader.array.TestArrays.shorts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionStorageTest {

    @Nested
    class EpochInteger {

        @Test
        void readsEveryIntegerArrayType() {
            // Given / When / Then
            assertThat(ExtensionStorage.epochInteger(bytes((byte) -5), 0)).isEqualTo(-5L);
            assertThat(ExtensionStorage.epochInteger(shorts((short) 1234), 0)).isEqualTo(1234L);
            assertThat(ExtensionStorage.epochInteger(ints(70000), 0)).isEqualTo(70000L);
            assertThat(ExtensionStorage.epochInteger(longs(9_000_000_000L), 0)).isEqualTo(9_000_000_000L);
        }

        @Test
        void recursesThroughValidMaskedCell() {
            // Given
            MaskedArray masked = new MaskedArray(longs(10L, 20L), bools(true, true));

            // When / Then
            assertThat(ExtensionStorage.epochInteger(masked, 1)).isEqualTo(20L);
        }

        @Test
        void nullMaskedCell_throws() {
            // Given
            MaskedArray masked = new MaskedArray(longs(10L, 20L), bools(true, false));

            // When / Then
            assertThatThrownBy(() -> ExtensionStorage.epochInteger(masked, 1))
                    .isInstanceOf(VortexException.class).hasMessageContaining("null cell");
        }

        @Test
        void unsupportedStorage_throws() {
            // Given
            DoubleArray bad = doubles(1.0);

            // When / Then
            assertThatThrownBy(() -> ExtensionStorage.epochInteger(bad, 0))
                    .isInstanceOf(VortexException.class).hasMessageContaining("unsupported storage type");
        }
    }

    @Nested
    class ReadUnit {

        @Test
        void timeUnitMetadata_readsItsUnit() {
            // Given
            DType.Extension ext = ext(new io.github.dfa1.vortex.core.model.TimeUnitMetadata(TimeUnit.Seconds));

            // When
            TimeUnit result = ExtensionStorage.readUnit(ext);

            // Then
            assertThat(result).isEqualTo(TimeUnit.Seconds);
        }

        @Test
        void timestampMetadata_readsItsUnit() {
            // Given — a timestamp carries its unit alongside the timezone
            DType.Extension ext = ext(new io.github.dfa1.vortex.core.model.TimestampMetadata(TimeUnit.Microseconds, null));

            // When
            TimeUnit result = ExtensionStorage.readUnit(ext);

            // Then
            assertThat(result).isEqualTo(TimeUnit.Microseconds);
        }

        @Test
        void opaqueMetadata_throws() {
            // Given metadata that records no unit
            DType.Extension ext = ext(io.github.dfa1.vortex.core.model.OpaqueMetadata.EMPTY);

            // When / Then
            assertThatThrownBy(() -> ExtensionStorage.readUnit(ext))
                    .isInstanceOf(VortexException.class).hasMessageContaining("missing TimeUnit");
        }

        private DType.Extension ext(io.github.dfa1.vortex.core.model.ExtensionMetadata metadata) {
            return new DType.Extension("vortex.timestamp", DTypes.I64, metadata, false);
        }
    }

    @Nested
    class InstantFromRaw {

        @Test
        void everyTimeUnitExceptDays() {
            // Given / When / Then
            assertThat(ExtensionStorage.instantFromRaw(5L, TimeUnit.Seconds))
                    .isEqualTo(Instant.ofEpochSecond(5));
            assertThat(ExtensionStorage.instantFromRaw(5000L, TimeUnit.Milliseconds))
                    .isEqualTo(Instant.ofEpochMilli(5000));
            assertThat(ExtensionStorage.instantFromRaw(1_500_000L, TimeUnit.Microseconds))
                    .isEqualTo(Instant.ofEpochSecond(1, 500_000_000L));
            assertThat(ExtensionStorage.instantFromRaw(1_500_000_000L, TimeUnit.Nanoseconds))
                    .isEqualTo(Instant.ofEpochSecond(1, 500_000_000L));
        }

        @Test
        void negativeMicrosFloorsCorrectly() {
            // Given / When / Then — floorDiv/floorMod path for negative epoch
            assertThat(ExtensionStorage.instantFromRaw(-1L, TimeUnit.Microseconds))
                    .isEqualTo(Instant.ofEpochSecond(-1, 999_999_000L));
            assertThat(ExtensionStorage.instantFromRaw(-1L, TimeUnit.Nanoseconds))
                    .isEqualTo(Instant.ofEpochSecond(-1, 999_999_999L));
        }

        @Test
        void daysRejected() {
            assertThatThrownBy(() -> ExtensionStorage.instantFromRaw(1L, TimeUnit.Days))
                    .isInstanceOf(VortexException.class).hasMessageContaining("Days");
        }
    }

    @Nested
    class CheckBounds {

        @Test
        void validIndexPasses() {
            ExtensionStorage.checkBounds(0, 3);
            ExtensionStorage.checkBounds(2, 3);
        }

        @Test
        void outOfBoundsThrows() {
            assertThatThrownBy(() -> ExtensionStorage.checkBounds(3, 3))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> ExtensionStorage.checkBounds(-1, 3))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }
}
