package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static io.github.dfa1.vortex.reader.array.TestArrays.ints;
import static io.github.dfa1.vortex.reader.array.TestArrays.longs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Covers the decoded list container arrays (variable-length and fixed-size).
class ListArraysTest {

    private static final DType ELEM = DType.I32;

    @Nested
    class VariableLength {

        private final DType.List dtype = new DType.List(ELEM, false);
        private final IntArray elements = ints(10, 11, 12, 13, 14);
        private final Array offsets = longs(0L, 2L, 5L); // list0=[10,11], list1=[12,13,14]
        private final ListArray sut = new ListArray(dtype, 2, elements, offsets);

        @Test
        void exposesShapeAndChildren() {
            // When / Then
            assertThat(sut.length()).isEqualTo(2);
            assertThat(sut.dtype()).isEqualTo(dtype);
            assertThat(sut.elements()).isSameAs(elements);
            assertThat(sut.offsets()).isSameAs(offsets);
            assertThat(sut.child(0)).isSameAs(elements);
            assertThat(sut.child(1)).isSameAs(offsets);
        }

        @Test
        void childOutOfRangeThrows() {
            assertThatThrownBy(() -> sut.child(2))
                    .isInstanceOf(VortexException.class).hasMessageContaining("child index");
        }

        @Test
        void limitedKeepsRowsAndSharesElements() {
            // When
            ListArray limited = (ListArray) sut.limited(1);

            // Then — one list, offsets trimmed to rows+1, elements shared
            assertThat(limited.length()).isEqualTo(1);
            assertThat(limited.offsets().length()).isEqualTo(2);
            assertThat(limited.elements()).isSameAs(elements);
        }

        @Test
        void materializeThrows() {
            try (Arena arena = Arena.ofConfined()) {
                assertThatThrownBy(() -> sut.materialize(arena))
                        .isInstanceOf(VortexException.class).hasMessageContaining("no primary segment");
            }
        }
    }

    @Nested
    class FixedSize {

        private final DType.FixedSizeList dtype = new DType.FixedSizeList(ELEM, 2, false);
        private final IntArray elements = ints(1, 2, 3, 4, 5, 6);
        private final FixedSizeListArray sut = new FixedSizeListArray(dtype, 3, elements);

        @Test
        void exposesShapeAndChildren() {
            // When / Then
            assertThat(sut.length()).isEqualTo(3);
            assertThat(sut.dtype()).isEqualTo(dtype);
            assertThat(sut.elements()).isSameAs(elements);
            assertThat(sut.fixedSize()).isEqualTo(2);
            assertThat(sut.child(0)).isSameAs(elements);
        }

        @Test
        void childOutOfRangeThrows() {
            assertThatThrownBy(() -> sut.child(1))
                    .isInstanceOf(ArrayIndexOutOfBoundsException.class);
        }

        @Test
        void limitedKeepsRowsTimesFixedSizeElements() {
            // When
            FixedSizeListArray limited = (FixedSizeListArray) sut.limited(2);

            // Then — 2 rows × fixedSize 2 = 4 elements
            assertThat(limited.length()).isEqualTo(2);
            assertThat(limited.elements().length()).isEqualTo(4);
        }

        @Test
        void materializeThrows() {
            try (Arena arena = Arena.ofConfined()) {
                assertThatThrownBy(() -> sut.materialize(arena))
                        .isInstanceOf(VortexException.class).hasMessageContaining("no primary segment");
            }
        }
    }
}
