package io.github.dfa1.vortex.reader.array;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static io.github.dfa1.vortex.core.testing.DTypes.I64;
import static io.github.dfa1.vortex.reader.array.TestArrays.bools;
import static io.github.dfa1.vortex.reader.array.TestArrays.ints;
import static io.github.dfa1.vortex.reader.array.TestArrays.longs;
import static org.assertj.core.api.Assertions.assertThat;

class CanonicalArraysTest {

    @Test
    void of_lazyNumeric_materializedWithSameValues() {
        // Given a run-end array, whose indexed reads binary-search per row
        LongArray lazy = new LazyRunEndLongArray(I64, 4, longs(7L, 9L), ints(1, 4), 0L);
        try (Arena arena = Arena.ofConfined()) {

            // When
            Array result = CanonicalArrays.of(lazy, arena);

            // Then a flat buffer holding the same rows
            assertThat(result).isInstanceOf(MaterializedLongArray.class);
            LongArray flat = (LongArray) result;
            assertThat(new long[]{flat.getLong(0), flat.getLong(1), flat.getLong(2), flat.getLong(3)})
                    .containsExactly(7L, 9L, 9L, 9L);
            assertThat(result.dtype()).isEqualTo(lazy.dtype());
        }
    }

    @Test
    void of_segmentBacked_returnedUnchanged() {
        // Given an array already backed by a segment (possibly a broadcast one): copying it would
        // only cost memory, and a broadcast buffer must keep its broadcasting accessor
        LongArray flat = longs(1L, 2L);

        // When
        Array result = CanonicalArrays.of(flat, Arena.ofAuto());

        // Then
        assertThat(result).isSameAs(flat);
    }

    @Test
    void of_masked_canonicalizesInnerAndKeepsValidity() {
        // Given
        BoolArray validity = bools(true, false, true, true);
        var masked = new MaskedArray(new LazyRunEndLongArray(I64, 4, longs(7L, 9L), ints(1, 4), 0L), validity);
        try (Arena arena = Arena.ofConfined()) {

            // When
            Array result = CanonicalArrays.of(masked, arena);

            // Then
            assertThat(result).isInstanceOf(MaskedArray.class);
            assertThat(((MaskedArray) result).inner()).isInstanceOf(MaterializedLongArray.class);
            assertThat(((MaskedArray) result).validity()).isSameAs(validity);
        }
    }
}
