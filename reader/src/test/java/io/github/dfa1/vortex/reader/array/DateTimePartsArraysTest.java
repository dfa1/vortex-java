package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Test;

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

    @Test
    void readLong_recursesThroughValidMaskedCell() {
        // Given — a masked array whose cell is valid
        MaskedArray masked = new MaskedArray(longs(42L, 43L), bools(true, true));

        // When / Then — unwraps to the inner value
        assertThat(DateTimePartsArrays.readLong(masked, 1)).isEqualTo(43L);
    }

    @Test
    void readLong_nullMaskedCell_throws() {
        // Given — a masked array whose cell is null
        MaskedArray masked = new MaskedArray(longs(42L, 43L), bools(true, false));

        // When / Then
        assertThatThrownBy(() -> DateTimePartsArrays.readLong(masked, 1))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("null cell");
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
