package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LazyByteBoolArrayTest {

    private static final DType BOOL = DType.BOOL;

    @Nested
    class Accessors {

        /// Any non-zero byte is `true`, not just 1 — the encoder writes 1, but the format does
        /// not promise it and a Rust-written file may carry other values.
        @ParameterizedTest
        @CsvSource({"0,false", "1,true", "2,true", "42,true", "-1,true", "-128,true"})
        void getBoolean_treatsAnyNonZeroByteAsTrue(byte value, boolean expected) {
            // Given
            LazyByteBoolArray sut = new LazyByteBoolArray(BOOL, 1, bytes(value));

            // When
            boolean result = sut.getBoolean(0);

            // Then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        void getBoolean_resolvesEachRowIndependently() {
            // Given
            LazyByteBoolArray sut = new LazyByteBoolArray(BOOL, 4, bytes((byte) 0, (byte) 1, (byte) 0, (byte) 1));

            // When / Then
            assertThat(sut.getBoolean(0)).isFalse();
            assertThat(sut.getBoolean(1)).isTrue();
            assertThat(sut.getBoolean(2)).isFalse();
            assertThat(sut.getBoolean(3)).isTrue();
        }

        @Test
        void getBoolean_outOfBoundsIndex_throws() {
            // Given
            LazyByteBoolArray sut = new LazyByteBoolArray(BOOL, 2, bytes((byte) 1, (byte) 0));

            // When / Then
            assertThatThrownBy(() -> sut.getBoolean(2)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        /// A shorter `length` than the buffer holds is how a sliced or trimmed column arrives;
        /// the trailing bytes must stay invisible.
        @Test
        void getBoolean_lengthShorterThanBuffer_hidesTrailingBytes() {
            // Given — 4 bytes of data but only 2 rows claimed
            LazyByteBoolArray sut = new LazyByteBoolArray(BOOL, 2, bytes((byte) 1, (byte) 0, (byte) 1, (byte) 1));

            // When / Then
            assertThat(sut.getBoolean(1)).isFalse();
            assertThatThrownBy(() -> sut.getBoolean(2)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        void forEachBoolean_visitsEveryRowInOrder() {
            // Given
            LazyByteBoolArray sut = new LazyByteBoolArray(BOOL, 3, bytes((byte) 1, (byte) 0, (byte) 1));
            List<Boolean> seen = new ArrayList<>();

            // When
            sut.forEachBoolean(seen::add);

            // Then
            assertThat(seen).containsExactly(true, false, true);
        }
    }

    @Nested
    class Representation {

        /// The byte-per-row buffer is not the LSB-first bitmap a caller asking a bool array for
        /// its segment expects, so it must not be handed over as one.
        @Test
        void segmentIfPresent_isEmpty() {
            // Given
            LazyByteBoolArray sut = new LazyByteBoolArray(BOOL, 2, bytes((byte) 1, (byte) 0));

            // When / Then
            assertThat(sut.segmentIfPresent()).isEmpty();
        }

        /// The bit-packing the decoder used to do eagerly, now on demand: bits are LSB-first,
        /// so rows 0 and 9 land in bit 0 of bytes 0 and 1.
        @Test
        void materialize_packsLsbFirstBitmap() {
            // Given — 10 rows, true at 0 and 9
            byte[] raw = new byte[10];
            raw[0] = 1;
            raw[9] = 1;
            LazyByteBoolArray sut = new LazyByteBoolArray(BOOL, 10, bytes(raw));

            // When
            MemorySegment result = sut.materialize(Arena.ofAuto());

            // Then
            assertThat(result.byteSize()).isEqualTo(2);
            assertThat(result.get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 0b0000_0001);
            assertThat(result.get(ValueLayout.JAVA_BYTE, 1)).isEqualTo((byte) 0b0000_0010);
        }

        /// Round-trip through the bitmap must agree with reading the bytes directly — the two
        /// are what a consumer picks between, so they cannot disagree.
        @Test
        void materialize_agreesWithGetBoolean() {
            // Given — 20 rows with an irregular pattern, so a byte-boundary slip is visible
            byte[] raw = new byte[20];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (byte) (i % 3 == 0 ? 1 : 0);
            }
            LazyByteBoolArray sut = new LazyByteBoolArray(BOOL, raw.length, bytes(raw));

            // When
            MemorySegment result = sut.materialize(Arena.ofAuto());

            // Then
            BoolArray packed = new MaterializedBoolArray(BOOL, raw.length, result);
            for (long i = 0; i < raw.length; i++) {
                assertThat(packed.getBoolean(i)).as("row %d", i).isEqualTo(sut.getBoolean(i));
            }
        }

        @Test
        void limited_capsTheRowCount() {
            // Given
            LazyByteBoolArray sut = new LazyByteBoolArray(BOOL, 4, bytes((byte) 1, (byte) 0, (byte) 1, (byte) 1));

            // When
            Array result = sut.limited(2);

            // Then
            assertThat(result.length()).isEqualTo(2);
            assertThat(((BoolArray) result).getBoolean(1)).isFalse();
        }
    }

    private static MemorySegment bytes(byte... values) {
        return MemorySegment.ofArray(values);
    }
}
