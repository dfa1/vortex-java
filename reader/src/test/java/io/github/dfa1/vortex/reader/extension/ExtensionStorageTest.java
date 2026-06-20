package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
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
        void readsTagByte() {
            // Given — tag 3 = Seconds (ordinal)
            DType.Extension ext = ext(ByteBuffer.wrap(new byte[]{3}));

            // When / Then
            assertThat(ExtensionStorage.readUnit(ext)).isEqualTo(TimeUnit.Seconds);
        }

        @Test
        void nullMetadata_throws() {
            assertThatThrownBy(() -> ExtensionStorage.readUnit(ext(null)))
                    .isInstanceOf(VortexException.class).hasMessageContaining("missing TimeUnit");
        }

        @Test
        void emptyMetadata_throws() {
            assertThatThrownBy(() -> ExtensionStorage.readUnit(ext(ByteBuffer.allocate(0))))
                    .isInstanceOf(VortexException.class).hasMessageContaining("missing TimeUnit");
        }

        private DType.Extension ext(ByteBuffer meta) {
            return new DType.Extension("vortex.timestamp", new DType.Primitive(PType.I64, false), meta, false);
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
