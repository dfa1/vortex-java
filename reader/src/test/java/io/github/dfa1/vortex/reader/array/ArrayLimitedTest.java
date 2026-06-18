package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests the [Array#limited(long)] contract: the [Array#limited(Array, long)] guard
/// (no-op clamp + negative rejection) and every concrete implementation —
/// zero-copy views, composite child recursion, and the [UnknownArray] rejection.
class ArrayLimitedTest {

    private static final DType I64 = new DType.Primitive(PType.I64, false);

    @Nested
    class Guard {

        @Test
        void rowsEqualToLengthReturnsSameInstance() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                LongArray sut = longs(arena, 1L, 2L, 3L);

                // When
                Array result = Array.limited(sut, 3);

                // Then
                assertThat(result).isSameAs(sut);
            }
        }

        @Test
        void rowsBiggerThanLengthReturnsSameInstance() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — limit is min(length, rows): asking for more than exists is a no-op
                LongArray sut = longs(arena, 1L, 2L, 3L);

                // When
                Array result = Array.limited(sut, 99);

                // Then
                assertThat(result).isSameAs(sut);
            }
        }

        @Test
        void negativeRowsThrows() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                LongArray sut = longs(arena, 1L);

                // When / Then
                assertThatThrownBy(() -> Array.limited(sut, -1))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(">= 0");
            }
        }
    }

    @Nested
    class Primitive {

        @Test
        void cutsToFirstRowsAsView() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                LongArray sut = longs(arena, 10L, 20L, 30L, 40L);

                // When
                Array result = sut.limited(2);

                // Then
                assertThat(result.length()).isEqualTo(2L);
                assertThat(((LongArray) result).getLong(0)).isEqualTo(10L);
                assertThat(((LongArray) result).getLong(1)).isEqualTo(20L);
            }
        }

        @Test
        void float16SlicesBuffer() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                Float16Array sut = float16(arena, 1.0f, 2.0f, 3.0f);

                // When
                Array result = sut.limited(2);

                // Then
                assertThat(result.length()).isEqualTo(2L);
                assertThat(((Float16Array) result).getFloat(0)).isEqualTo(1.0f);
                assertThat(((Float16Array) result).getFloat(1)).isEqualTo(2.0f);
            }
        }
    }

    @Nested
    class Composite {

        @Test
        void structLimitsEachField() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                DType.Struct dtype = new DType.Struct(List.of("a", "b"), List.of(I64, I64), false);
                StructArray sut = new StructArray(dtype, 3,
                        List.of(longs(arena, 1L, 2L, 3L), longs(arena, 10L, 20L, 30L)));

                // When
                StructArray result = (StructArray) sut.limited(2);

                // Then
                assertThat(result.length()).isEqualTo(2L);
                assertThat(result.field(0).length()).isEqualTo(2L);
                assertThat(((LongArray) result.field(1)).getLong(1)).isEqualTo(20L);
            }
        }

        @Test
        void listLimitsOffsetsToRowsPlusOne() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — 3 lists over offsets [0,2,3,5]; elements shared
                DType.List dtype = new DType.List(I64, false);
                LongArray elements = longs(arena, 7L, 7L, 8L, 9L, 9L);
                LongArray offsets = longs(arena, 0L, 2L, 3L, 5L);
                ListArray sut = new ListArray(dtype, 3, elements, offsets);

                // When
                ListArray result = (ListArray) sut.limited(2);

                // Then — offsets keep rows+1 = 3 entries so list[1] bounds stay readable
                assertThat(result.length()).isEqualTo(2L);
                assertThat(result.offsets().length()).isEqualTo(3L);
                assertThat(result.elements()).isSameAs(elements);
            }
        }

        @Test
        void listViewLimitsOffsetsAndSizes() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                DType.List dtype = new DType.List(I64, false);
                LongArray elements = longs(arena, 1L, 2L, 3L, 4L);
                LongArray offsets = longs(arena, 0L, 2L, 3L);
                LongArray sizes = longs(arena, 2L, 1L, 1L);
                ListViewArray sut = new ListViewArray(dtype, 3, elements, offsets, sizes);

                // When
                ListViewArray result = (ListViewArray) sut.limited(2);

                // Then
                assertThat(result.length()).isEqualTo(2L);
                assertThat(result.offsets().length()).isEqualTo(2L);
                assertThat(result.sizes().length()).isEqualTo(2L);
            }
        }

        @Test
        void fixedSizeListLimitsElementsByWidth() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — fixedSize 2: 3 rows -> 6 elements
                DType.FixedSizeList dtype = new DType.FixedSizeList(I64, 2, false);
                FixedSizeListArray sut = new FixedSizeListArray(dtype, 3,
                        longs(arena, 1L, 2L, 3L, 4L, 5L, 6L));

                // When
                FixedSizeListArray result = (FixedSizeListArray) sut.limited(2);

                // Then — 2 rows -> 4 elements
                assertThat(result.length()).isEqualTo(2L);
                assertThat(result.elements().length()).isEqualTo(4L);
            }
        }

        @Test
        void variantLimitsCoreAndShredded() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                VariantArray sut = new VariantArray(I64, 3,
                        longs(arena, 1L, 2L, 3L), longs(arena, 4L, 5L, 6L));

                // When
                VariantArray result = (VariantArray) sut.limited(2);

                // Then
                assertThat(result.length()).isEqualTo(2L);
                assertThat(result.coreStorage().length()).isEqualTo(2L);
                assertThat(result.shredded().length()).isEqualTo(2L);
            }
        }

        @Test
        void variantWithNullShreddedStaysNull() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                VariantArray sut = new VariantArray(I64, 3, longs(arena, 1L, 2L, 3L), null);

                // When
                VariantArray result = (VariantArray) sut.limited(2);

                // Then
                assertThat(result.shredded()).isNull();
            }
        }
    }

    @Nested
    class Unsupported {

        @Test
        void unknownArrayThrows() {
            // Given
            UnknownArray sut = new UnknownArray("vortex.mystery", I64, 3, null,
                    new MemorySegment[0], new Array[0]);

            // When / Then
            assertThatThrownBy(() -> sut.limited(1))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("vortex.mystery");
        }
    }

    private static LongArray longs(Arena arena, long... vs) {
        MemorySegment seg = arena.allocate(vs.length * 8L, 8);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_LONG, i, vs[i]);
        }
        return new MaterializedLongArray(I64, vs.length, seg.asReadOnly());
    }

    private static Float16Array float16(Arena arena, float... vs) {
        MemorySegment seg = arena.allocate(vs.length * 2L, 2);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_SHORT, i, Float.floatToFloat16(vs[i]));
        }
        return new MaterializedFloat16Array(new DType.Primitive(PType.F16, false), vs.length, seg.asReadOnly());
    }
}
