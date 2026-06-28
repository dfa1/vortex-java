package io.github.dfa1.vortex.reader.compute;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("removal") // transitional — exercises the deprecated Mask until the fused multi-column filteredReduce lands
class MaskTest {

    @Nested
    class AllTrue {

        @Test
        void selectsEveryPosition() {
            // Given an all-true mask of three rows
            Mask sut = Mask.allTrue(3);

            // When counting selected rows
            long result = sut.trueCount();

            // Then every position is selected and the count equals the length
            assertThat(result).isEqualTo(3);
            assertThat(sut.length()).isEqualTo(3);
            assertThat(sut.get(0)).isTrue();
            assertThat(sut.get(1)).isTrue();
            assertThat(sut.get(2)).isTrue();
        }

        @Test
        void emptyMaskHasNoRows() {
            // Given a zero-length mask — the boundary where get must reject every index
            Mask sut = Mask.allTrue(0);

            // When inspecting length and count
            long result = sut.trueCount();

            // Then it covers nothing and any access is out of bounds
            assertThat(sut.length()).isZero();
            assertThat(result).isZero();
            assertThatThrownBy(() -> sut.get(0)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        void getRejectsOutOfBounds() {
            // Given a single-element mask
            Mask sut = Mask.allTrue(1);

            // When reading at length-1 (valid) and at length (one past the end)
            boolean result = sut.get(0);

            // Then the in-bounds read works and the just-past-end read throws
            assertThat(result).isTrue();
            assertThatThrownBy(() -> sut.get(1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        void rejectsNegativeLength() {
            // Given a negative length
            // When constructing an all-true mask
            // Then it is rejected
            assertThatThrownBy(() -> Mask.allTrue(-1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class AllFalse {

        @Test
        void selectsNoPosition() {
            // Given an all-false mask of three rows
            Mask sut = Mask.allFalse(3);

            // When counting selected rows
            long result = sut.trueCount();

            // Then nothing is selected even though the mask still spans the length
            assertThat(result).isZero();
            assertThat(sut.length()).isEqualTo(3);
            assertThat(sut.get(0)).isFalse();
            assertThat(sut.get(2)).isFalse();
        }

        @Test
        void emptyMaskHasNoRows() {
            // Given a zero-length all-false mask
            Mask sut = Mask.allFalse(0);

            // When inspecting the count
            long result = sut.trueCount();

            // Then it is empty and rejects any access
            assertThat(sut.length()).isZero();
            assertThat(result).isZero();
            assertThatThrownBy(() -> sut.get(0)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        void getRejectsOutOfBounds() {
            // Given a single-element all-false mask
            Mask sut = Mask.allFalse(1);

            // When reading at the only valid index then one past the end
            boolean result = sut.get(0);

            // Then the in-bounds read is false and out-of-bounds reads throw
            assertThat(result).isFalse();
            assertThatThrownBy(() -> sut.get(1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        void rejectsNegativeLength() {
            // Given a negative length
            // When constructing an all-false mask
            // Then it is rejected
            assertThatThrownBy(() -> Mask.allFalse(-1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class RangeMask {

        @Test
        void midRangeSelectsOnlyTheRun() {
            // Given a mask selecting [2, 5) within 8 rows
            Mask sut = new Mask.RangeMask(2, 5, 8);

            // When counting selected rows
            long result = sut.trueCount();

            // Then only [2, 5) is selected and the count is the run width
            assertThat(result).isEqualTo(3);
            assertThat(sut.length()).isEqualTo(8);
            assertThat(sut.get(1)).isFalse();
            assertThat(sut.get(2)).isTrue();
            assertThat(sut.get(4)).isTrue();
            assertThat(sut.get(5)).isFalse();
        }

        @Test
        void fullRangeSelectsEverything() {
            // Given a range that spans the whole length — the LIMIT-everything boundary
            Mask sut = new Mask.RangeMask(0, 4, 4);

            // When counting selected rows
            long result = sut.trueCount();

            // Then it behaves like all-true: endpoints included, count equals length
            assertThat(result).isEqualTo(4);
            assertThat(sut.get(0)).isTrue();
            assertThat(sut.get(3)).isTrue();
        }

        @Test
        void emptyRangeSelectsNothing() {
            // Given an empty range (start == end) — produced by LIMIT 0 / an empty SLICE
            Mask sut = new Mask.RangeMask(3, 3, 8);

            // When counting selected rows
            long result = sut.trueCount();

            // Then nothing is selected though the mask still spans all 8 rows
            assertThat(result).isZero();
            assertThat(sut.get(3)).isFalse();
        }

        @Test
        void zeroLengthAllowsOnlyEmptyRange() {
            // Given the degenerate zero-length range — both bounds pinned to 0
            Mask sut = new Mask.RangeMask(0, 0, 0);

            // When inspecting it
            long result = sut.trueCount();

            // Then it is empty and rejects any access
            assertThat(result).isZero();
            assertThatThrownBy(() -> sut.get(0)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        void getRejectsOutOfBounds() {
            // Given a range mask over 4 rows; get bounds-checks against length, not the run
            Mask sut = new Mask.RangeMask(1, 3, 4);

            // When reading at the last valid index
            boolean result = sut.get(3);

            // Then length-1 is readable (and false, being past the run) while out-of-bounds throws
            assertThat(result).isFalse();
            assertThatThrownBy(() -> sut.get(4)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        void rejectsStartAfterEnd() {
            // Given start greater than end
            // When constructing
            // Then it is rejected
            assertThatThrownBy(() -> new Mask.RangeMask(5, 2, 8))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsEndBeyondLength() {
            // Given end past the covered length
            // When constructing
            // Then it is rejected
            assertThatThrownBy(() -> new Mask.RangeMask(0, 9, 8))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegativeStart() {
            // Given a negative start
            // When constructing
            // Then it is rejected
            assertThatThrownBy(() -> new Mask.RangeMask(-1, 3, 8))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegativeLength() {
            // Given a negative length (end <= length can never hold for a sane range)
            // When constructing
            // Then it is rejected
            assertThatThrownBy(() -> new Mask.RangeMask(0, 0, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class BitmapMask {

        @Test
        void readsBitsLsbFirstWithinByte() {
            // Given a single byte 0b0000_1010 = positions 1 and 3 set. This pattern is deliberately
            // asymmetric (not 0xFF / 0x00) so it pins the bit ORDER: an MSB-first reader would
            // report positions 4 and 6 instead, so this catches an inverted bit-order bug.
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bits = arena.allocate(1);
                bits.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b0000_1010);
                Mask sut = new Mask.BitmapMask(bits, 8);

                // When probing the low four positions
                boolean result = sut.get(1);

                // Then only positions 1 and 3 are selected, LSB-first
                assertThat(result).isTrue();
                assertThat(sut.get(0)).isFalse();
                assertThat(sut.get(2)).isFalse();
                assertThat(sut.get(3)).isTrue();
                assertThat(sut.trueCount()).isEqualTo(2);
            }
        }

        @Test
        void readsAcrossByteBoundariesLittleEndian() {
            // Given two bytes [0x01, 0x80]: bit 0 of byte 0 (position 0) and bit 7 of byte 1
            // (position 15). The high byte holding the high position pins little-endian byte order —
            // a big-endian reader would map position 15 into the first byte and miss it.
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bits = arena.allocate(2);
                bits.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x01);
                bits.set(ValueLayout.JAVA_BYTE, 1, (byte) 0x80);
                Mask sut = new Mask.BitmapMask(bits, 16);

                // When probing the first and last positions
                boolean result = sut.get(15);

                // Then position 0 and position 15 are the only ones set
                assertThat(result).isTrue();
                assertThat(sut.get(0)).isTrue();
                assertThat(sut.get(8)).isFalse();
                assertThat(sut.trueCount()).isEqualTo(2);
            }
        }

        @Test
        void allBitsSet() {
            // Given a fully-set byte covering all 8 positions
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bits = arena.allocate(1);
                bits.set(ValueLayout.JAVA_BYTE, 0, (byte) 0xFF);
                Mask sut = new Mask.BitmapMask(bits, 8);

                // When counting set bits
                long result = sut.trueCount();

                // Then every position is selected
                assertThat(result).isEqualTo(8);
                assertThat(sut.get(0)).isTrue();
                assertThat(sut.get(7)).isTrue();
            }
        }

        @Test
        void allBitsClear() {
            // Given an all-zero byte
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bits = arena.allocate(1);
                Mask sut = new Mask.BitmapMask(bits, 8);

                // When counting set bits
                long result = sut.trueCount();

                // Then nothing is selected
                assertThat(result).isZero();
                assertThat(sut.get(0)).isFalse();
            }
        }

        @Test
        void alternatingBits() {
            // Given 0b1010_1010 = odd positions set, even positions clear
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bits = arena.allocate(1);
                bits.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b1010_1010);
                Mask sut = new Mask.BitmapMask(bits, 8);

                // When checking parity of selected positions
                long result = sut.trueCount();

                // Then exactly the four odd positions are selected
                assertThat(result).isEqualTo(4);
                for (int i = 0; i < 8; i++) {
                    assertThat(sut.get(i)).isEqualTo(i % 2 == 1);
                }
            }
        }

        @Test
        void trueCountIgnoresTrailingBitsBeyondLength() {
            // Given a byte 0xFF but a mask of length 3: only positions 0..2 are real. Bits 3..7 are
            // trailing padding that must NOT be counted — this catches a popcount that naively counts
            // whole bytes and over-reports 8 instead of 3.
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bits = arena.allocate(1);
                bits.set(ValueLayout.JAVA_BYTE, 0, (byte) 0xFF);
                Mask sut = new Mask.BitmapMask(bits, 3);

                // When counting set bits within the live length
                long result = sut.trueCount();

                // Then only the three live positions count, and access stops at the length
                assertThat(result).isEqualTo(3);
                assertThat(sut.get(2)).isTrue();
                assertThatThrownBy(() -> sut.get(3)).isInstanceOf(IndexOutOfBoundsException.class);
            }
        }

        @ParameterizedTest
        @ValueSource(longs = {0, 1, 7, 8, 9, 63, 64, 65, 128, 200})
        void trueCountMatchesPopcountAtByteBoundaries(long length) {
            // Given a bitmap where every live position is set, across lengths straddling byte and
            // word boundaries (7/8/9, 63/64/65, plus multi-word 128/200) where partial-final-word
            // masking is most error-prone
            try (Arena arena = Arena.ofConfined()) {
                long byteCount = Math.max(1, (length + 7) >>> 3);
                MemorySegment bits = arena.allocate(byteCount);
                for (long b = 0; b < byteCount; b++) {
                    bits.set(ValueLayout.JAVA_BYTE, b, (byte) 0xFF);
                }
                Mask sut = new Mask.BitmapMask(bits, length);

                // When counting set bits
                long result = sut.trueCount();

                // Then the count equals the length (all live bits set), ignoring padding
                assertThat(result).isEqualTo(length);
            }
        }

        @Test
        void emptyBitmapHasNoRows() {
            // Given a zero-length bitmap over an empty segment
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bits = arena.allocate(0);
                Mask sut = new Mask.BitmapMask(bits, 0);

                // When inspecting it
                long result = sut.trueCount();

                // Then it is empty and rejects any access
                assertThat(sut.length()).isZero();
                assertThat(result).isZero();
                assertThatThrownBy(() -> sut.get(0)).isInstanceOf(IndexOutOfBoundsException.class);
            }
        }

        @Test
        void rejectsSegmentTooSmall() {
            // Given a 1-byte segment but a mask claiming 9 positions (needs 2 bytes) — guards against
            // an out-of-bounds segment read on get/popcount
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bits = arena.allocate(1);

                // When constructing past the segment's capacity
                // Then it is rejected up front
                assertThatThrownBy(() -> new Mask.BitmapMask(bits, 9))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void rejectsNegativeLength() {
            // Given a negative length
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bits = arena.allocate(1);

                // When constructing
                // Then it is rejected
                assertThatThrownBy(() -> new Mask.BitmapMask(bits, -1))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void rejectsNullSegment() {
            // Given a null segment
            // When constructing
            // Then it is rejected
            assertThatThrownBy(() -> new Mask.BitmapMask(null, 0))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void equalWhenSameLengthAndLiveBits() {
            // Given two masks built from distinct segments holding the same multi-word bit pattern
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment a = patterned(arena, 25, 200);
                MemorySegment b = patterned(arena, 25, 200);
                Mask sut = new Mask.BitmapMask(a, 200);

                // When comparing against an equal mask
                Mask result = new Mask.BitmapMask(b, 200);

                // Then they are value-equal with matching hash codes
                assertThat(sut).isEqualTo(result);
                assertThat(sut).hasSameHashCodeAs(result);
            }
        }

        @Test
        void unequalWhenLengthsDiffer() {
            // Given two masks over identical bytes but covering a different number of live rows
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment a = patterned(arena, 2, 16);
                MemorySegment b = patterned(arena, 2, 16);
                Mask sut = new Mask.BitmapMask(a, 16);

                // When comparing against a shorter mask
                Mask result = new Mask.BitmapMask(b, 8);

                // Then differing lengths make them unequal
                assertThat(sut).isNotEqualTo(result);
            }
        }

        @Test
        void equalWhenOnlyPaddingBitsDiffer() {
            // Given two length-3 masks whose live bits 0..2 match but whose padding bits 3..7 differ
            // (0b0000_0101 vs 0b1110_0101) — padding past length must NOT affect equality
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment a = arena.allocate(1);
                a.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b0000_0101);
                MemorySegment b = arena.allocate(1);
                b.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b1110_0101);
                Mask sut = new Mask.BitmapMask(a, 3);

                // When comparing against the mask with differing padding bits
                Mask result = new Mask.BitmapMask(b, 3);

                // Then they are still equal with matching hash codes
                assertThat(sut).isEqualTo(result);
                assertThat(sut).hasSameHashCodeAs(result);
            }
        }

        @Test
        void unequalWhenLiveBitsDiffer() {
            // Given two length-8 masks differing in a live bit (bit 2 set vs clear)
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment a = arena.allocate(1);
                a.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b0000_0101);
                MemorySegment b = arena.allocate(1);
                b.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b0000_0001);
                Mask sut = new Mask.BitmapMask(a, 8);

                // When comparing against the mask with a differing live bit
                Mask result = new Mask.BitmapMask(b, 8);

                // Then they are unequal
                assertThat(sut).isNotEqualTo(result);
            }
        }

        private static MemorySegment patterned(Arena arena, long byteCount, long fill) {
            MemorySegment segment = arena.allocate(byteCount);
            for (long b = 0; b < byteCount; b++) {
                segment.set(ValueLayout.JAVA_BYTE, b, (byte) (fill + b));
            }
            return segment;
        }
    }
}
