package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeBitReaderTest {

    private static LeBitReader readerOf(byte... bytes) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(bytes.length);
            for (int i = 0; i < bytes.length; i++) {
                seg.set(ValueLayout.JAVA_BYTE, i, bytes[i]);
            }
            // Use auto arena so segment outlives this call
            MemorySegment copy = Arena.ofAuto().allocate(bytes.length);
            MemorySegment.copy(seg, 0, copy, 0, bytes.length);
            return new LeBitReader(copy);
        }
    }

    @Nested
    class ReadBits {

        @Test
        void zero_bits_returns_zero() {
            // Given
            var sut = readerOf((byte) 0xFF);

            // When
            long result = sut.readBits(0);

            // Then
            assertThat(result).isZero();
            assertThat(sut.bitsConsumed()).isZero();
        }

        @Test
        void single_byte_low_nibble() {
            // Given — 0b10110011 = 0xB3
            var sut = readerOf((byte) 0xB3);

            // When
            long result = sut.readBits(4);

            // Then — LSB first: bits [3:0] = 0b0011 = 3
            assertThat(result).isEqualTo(3L);
            assertThat(sut.bitsConsumed()).isEqualTo(4);
        }

        @Test
        void single_byte_high_nibble() {
            // Given — 0xB3 = 0b10110011
            var sut = readerOf((byte) 0xB3);

            // When — skip low nibble, read high nibble
            sut.readBits(4);
            long result = sut.readBits(4);

            // Then — bits [7:4] = 0b1011 = 11
            assertThat(result).isEqualTo(11L);
        }

        @Test
        void full_byte() {
            // Given
            var sut = readerOf((byte) 0xA7);

            // When
            long result = sut.readBits(8);

            // Then
            assertThat(result).isEqualTo(0xA7L);
        }

        @Test
        void cross_byte_boundary() {
            // Given — bytes: 0b11110000, 0b00001111 = 0xF0, 0x0F
            var sut = readerOf((byte) 0xF0, (byte) 0x0F);

            // When — read 4 bits (high nibble of 0xF0 = 0b1111=0xF), then 4 bits (low nibble of 0x0F = 0b1111=0xF... wait)
            // Actually: 0xF0 = 0b11110000. LSB-first: bits[0..3]=0b0000=0, bits[4..7]=0b1111=15
            // 0x0F = 0b00001111. bits[8..11]=0b1111=15, bits[12..15]=0b0000=0
            sut.readBits(4); // consume low nibble of 0xF0 (= 0)

            // Read 8 bits across byte boundary: high nibble of 0xF0 + low nibble of 0x0F
            long result = sut.readBits(8);

            // Then — 0b11110000 low4=0b1111, 0x0F high4=0b1111 → combined: 0b1111_1111 = 0xFF... wait
            // bits [4..7] of 0xF0 = (0xF0 >> 4) & 0xF = 0xF
            // bits [0..3] of 0x0F = 0x0F & 0xF = 0xF
            // 8-bit result: lower 4 from 0xF0 high nibble, upper 4 from 0x0F low nibble = 0xFF
            assertThat(result).isEqualTo(0xFFL);
        }

        @ParameterizedTest
        @CsvSource({
                "0,  0, 0",
                "1,  1, 1",
                "127, 7, 127",
                "255, 8, 255",
        })
        void read_n_bits_from_single_byte(int byteVal, int bitsToRead, long expected) {
            // Given
            var sut = readerOf((byte) byteVal);

            // When
            long result = sut.readBits(bitsToRead);

            // Then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        void readPastEnd_throwsVortexException() {
            // Given — 1-byte buffer
            var sut = readerOf((byte) 0xFF);
            sut.readBits(8); // consume all 8 bits

            // When / Then — reading 1 more bit must throw VortexException, not IOOBE
            assertThatThrownBy(() -> sut.readBits(1))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("truncated");
        }

        @Test
        void sequential_reads_advance_position() {
            // Given — 0xAB = 0b10101011
            var sut = readerOf((byte) 0xAB);

            // When
            long b0 = sut.readBits(1); // bit 0 = 1
            long b1 = sut.readBits(1); // bit 1 = 1
            long b2 = sut.readBits(1); // bit 2 = 0
            long b3 = sut.readBits(1); // bit 3 = 1

            // Then — 0xAB = 0b10101011, LSB first: 1,1,0,1,0,1,0,1
            assertThat(b0).isEqualTo(1);
            assertThat(b1).isEqualTo(1);
            assertThat(b2).isEqualTo(0);
            assertThat(b3).isEqualTo(1);
            assertThat(sut.bitsConsumed()).isEqualTo(4);
        }

        @Test
        void read_16_bits_across_two_bytes() {
            // Given — 0x34, 0x12 → LE 16-bit = 0x1234
            var sut = readerOf((byte) 0x34, (byte) 0x12);

            // When
            long result = sut.readBits(16);

            // Then
            assertThat(result).isEqualTo(0x1234L);
        }

        @Test
        void read_32_bits_across_four_bytes() {
            // Given — LE layout: bytes 0x78, 0x56, 0x34, 0x12 → 0x12345678
            var sut = readerOf((byte) 0x78, (byte) 0x56, (byte) 0x34, (byte) 0x12);

            // When
            long result = sut.readBits(32);

            // Then
            assertThat(result).isEqualTo(0x12345678L);
        }
    }

    @Nested
    class AlignToByte {

        @Test
        void already_aligned_is_noop() {
            // Given
            var sut = readerOf((byte) 0xFF, (byte) 0xAB);
            sut.readBits(8); // consume exactly one byte

            // When
            sut.alignToByte();

            // Then — byte offset unchanged
            assertThat(sut.byteOffset()).isEqualTo(1);
            assertThat(sut.bitsConsumed()).isEqualTo(8);
        }

        @Test
        void partial_byte_advances_to_next_byte() {
            // Given
            var sut = readerOf((byte) 0xFF, (byte) 0xAB);
            sut.readBits(3); // consume 3 bits of first byte

            // When
            sut.alignToByte();

            // Then — aligned to byte 1
            assertThat(sut.byteOffset()).isEqualTo(1);
            assertThat(sut.bitsConsumed()).isEqualTo(8);
        }

        @Test
        void next_read_after_align_reads_clean_byte() {
            // Given
            var sut = readerOf((byte) 0xFF, (byte) 0xAB);
            sut.readBits(3);
            sut.alignToByte();

            // When
            long result = sut.readBits(8);

            // Then
            assertThat(result).isEqualTo(0xABL);
        }
    }
}
