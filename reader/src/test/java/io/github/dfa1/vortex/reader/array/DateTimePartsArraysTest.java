package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.testing.DTypes;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static io.github.dfa1.vortex.reader.array.TestArrays.bools;
import static io.github.dfa1.vortex.reader.array.TestArrays.bytes;
import static io.github.dfa1.vortex.reader.array.TestArrays.doubles;
import static io.github.dfa1.vortex.reader.array.TestArrays.ints;
import static io.github.dfa1.vortex.reader.array.TestArrays.longs;
import static io.github.dfa1.vortex.reader.array.TestArrays.shorts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateTimePartsArraysTest {

    @Test
    void readLong_dispatchesEveryIntegerArrayType() {
        // Given / When / Then — each narrow integer type widens to signed long
        assertThat(DateTimePartsArrays.readLong(bytes((byte) -7), 0)).isEqualTo(-7L);
        assertThat(DateTimePartsArrays.readLong(shorts((short) 300), 0)).isEqualTo(300L);
        assertThat(DateTimePartsArrays.readLong(ints(70000), 0)).isEqualTo(70000L);
        assertThat(DateTimePartsArrays.readLong(longs(5_000_000_000L), 0)).isEqualTo(5_000_000_000L);
    }

    /// Seconds-within-day are encoded as U16 when the range fits (0–65 535, which covers
    /// all seconds in a day up to 18:12:15 UTC). Without zero-extension, values ≥ 32 768
    /// are sign-extended to negative shorts and produce a 2^16-second error in the
    /// reconstructed timestamp (#252). Reproduces with seconds = 43 443 (≈ 12:04 UTC,
    /// the tweet time from bi-euro2016 line 262 146 that first surfaced the bug).
    @Test
    void readLong_u16ShortArray_zeroExtends() {
        // Given — U16 ShortArray with a value that overflows signed I16 (43443 > 32767)
        short value = (short) 43_443;  // 0xA9B3 — fits U16, overflows I16 → -22093 if signed
        MemorySegment seg = Arena.ofAuto().allocate(2L, 2);
        seg.set(ValueLayout.JAVA_SHORT, 0, value);
        ShortArray u16 = new MaterializedShortArray(DTypes.U16, 1, seg.asReadOnly());

        // When
        long result = DateTimePartsArrays.readLong(u16, 0);

        // Then — zero-extended to 43443, NOT sign-extended to -22093
        assertThat(result).isEqualTo(43_443L);
    }

    @Test
    void readLong_u8ByteArray_zeroExtends() {
        // Given — U8 ByteArray with value 200 (> 127, sign-extends to -56 if treated as I8)
        MemorySegment seg = Arena.ofAuto().allocate(1L, 1);
        seg.set(ValueLayout.JAVA_BYTE, 0, (byte) 200);
        ByteArray u8 = new MaterializedByteArray(DTypes.U8, 1, seg.asReadOnly());

        // When
        long result = DateTimePartsArrays.readLong(u8, 0);

        // Then
        assertThat(result).isEqualTo(200L);
    }

    @Test
    void readLong_u32IntArray_zeroExtends() {
        // Given — U32 IntArray with value 2^31 + 1 (overflows signed I32)
        int value = Integer.MIN_VALUE + 1;  // same bits as 2147483649 unsigned
        MemorySegment seg = Arena.ofAuto().allocate(4L, 4);
        seg.set(ValueLayout.JAVA_INT, 0, value);
        IntArray u32 = new MaterializedIntArray(DTypes.U32, 1, seg.asReadOnly());

        // When
        long result = DateTimePartsArrays.readLong(u32, 0);

        // Then — 2^31 + 1, not -2^31 + 1
        assertThat(result).isEqualTo(2_147_483_649L);
    }

    @Test
    void readLong_recursesThroughValidMaskedCell() {
        // Given — a masked array whose cell is valid
        MaskedArray masked = new MaskedArray(longs(42L, 43L), bools(true, true));

        // When / Then — unwraps to the inner value
        assertThat(DateTimePartsArrays.readLong(masked, 1)).isEqualTo(43L);
    }

    @Test
    void readLong_nullMaskedCell_readsInnerFiller() {
        // Given — a masked array whose cell is null (validity false)
        MaskedArray masked = new MaskedArray(longs(42L, 43L), bools(true, false));

        // When — readLong ignores validity and returns the inner filler value; the
        // decoder tracks null rows via the reassembled array's own mask instead (#235)
        long result = DateTimePartsArrays.readLong(masked, 1);

        // Then
        assertThat(result).isEqualTo(43L);
    }

    @Test
    void readLong_unsupportedArrayType_throws() {
        // Given — a DoubleArray is not a valid date-time part child
        DoubleArray bad = doubles(1.0);

        // When / Then
        assertThatThrownBy(() -> DateTimePartsArrays.readLong(bad, 0))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("unsupported child array type");
    }
}
