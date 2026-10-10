package io.github.dfa1.vortex.writer.encode;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class LeBitWriterTest {

    @Test
    void writeBits_matchesABitByBitReference_forAnyWidthAndAlignment() {
        // Given — widths 0..64 in a random order at every pending-bit offset: short writes, the
        // wide ones (over 56 bits) split in two, full 64-bit writes and zero-width writes
        Random random = new Random(0xB17L);
        int writes = 20_000;
        long[] values = new long[writes];
        int[] widths = new int[writes];
        long totalBits = 0;
        for (int i = 0; i < writes; i++) {
            widths[i] = random.nextInt(4) == 0 ? 64 - random.nextInt(3) : random.nextInt(65);
            values[i] = random.nextLong();
            totalBits += widths[i];
        }
        byte[] expected = new byte[(int) ((totalBits + 7) / 8)];
        long position = 0;
        for (int i = 0; i < writes; i++) {
            for (int b = 0; b < widths[i]; b++, position++) {
                if ((values[i] >>> b & 1L) != 0) {
                    expected[(int) (position >>> 3)] |= (byte) (1 << (position & 7));
                }
            }
        }
        LeBitWriter sut = new LeBitWriter(1);

        // When
        for (int i = 0; i < writes; i++) {
            sut.writeBits(values[i], widths[i]);
        }
        MemorySegment result = sut.toMemorySegment(Arena.ofAuto());

        // Then
        assertThat(result.byteSize()).isEqualTo(expected.length);
        assertThat(result.toArray(ValueLayout.JAVA_BYTE)).containsExactly(expected);
    }

    @Test
    void writeBitsOfArrays_matchesOneWriteBitsPerValue() {
        // Given — a misaligned start, then a range mixing short, wide (over 56 bits, split in two
        // inside the batch loop) and zero widths, written past the initial capacity
        Random random = new Random(0xBA7CL);
        int writes = 5_000;
        long[] values = new long[writes];
        int[] widths = new int[writes];
        for (int i = 0; i < writes; i++) {
            widths[i] = random.nextInt(65);
            values[i] = random.nextLong();
        }
        LeBitWriter expected = new LeBitWriter(1);
        expected.writeBits(0b101, 3);
        for (int i = 10; i < writes - 10; i++) {
            expected.writeBits(values[i], widths[i]);
        }
        LeBitWriter sut = new LeBitWriter(1);
        sut.writeBits(0b101, 3);

        // When
        sut.writeBits(values, widths, 10, writes - 10);
        MemorySegment result = sut.toMemorySegment(Arena.ofAuto());

        // Then
        assertThat(result.toArray(ValueLayout.JAVA_BYTE))
            .containsExactly(expected.toMemorySegment(Arena.ofAuto()).toArray(ValueLayout.JAVA_BYTE));
    }

    @Test
    void reset_reusesTheBuffer_withoutLeakingEarlierBytes() {
        // Given — a long first page leaves non-zero bytes past where the second one ends
        LeBitWriter sut = new LeBitWriter(1);
        for (int i = 0; i < 100; i++) {
            sut.writeBits(-1L, 64);
        }
        sut.reset();

        // When
        sut.writeBits(0b1, 1);
        sut.writeBits(0, 10);
        MemorySegment result = sut.toMemorySegment(Arena.ofAuto());

        // Then
        assertThat(result.toArray(ValueLayout.JAVA_BYTE)).containsExactly((byte) 0b1, (byte) 0);
    }

    @Test
    void alignToByte_padsTheLastPartialByteWithZeros_andIsRepeatable() {
        // Given
        LeBitWriter sut = new LeBitWriter(4);
        sut.writeBits(0b101, 3);

        // When
        sut.alignToByte();
        sut.alignToByte();
        sut.writeBits(0xFF, 8);
        MemorySegment result = sut.toMemorySegment(Arena.ofAuto());

        // Then — the padding is zeros, a second align adds nothing, the next write starts a new byte
        assertThat(result.toArray(ValueLayout.JAVA_BYTE)).containsExactly((byte) 0b101, (byte) 0xFF);
    }
}
