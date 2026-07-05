package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static io.github.dfa1.vortex.encoding.DTypes.BOOL;
import static io.github.dfa1.vortex.encoding.DTypes.F32;
import static io.github.dfa1.vortex.encoding.DTypes.F64;
import static io.github.dfa1.vortex.encoding.DTypes.I16;
import static io.github.dfa1.vortex.encoding.DTypes.I32;
import static io.github.dfa1.vortex.encoding.DTypes.I64;
import static io.github.dfa1.vortex.encoding.DTypes.I8;
import static io.github.dfa1.vortex.reader.array.TestArrays.bools;
import static io.github.dfa1.vortex.reader.array.TestArrays.bytes;
import static io.github.dfa1.vortex.reader.array.TestArrays.doubles;
import static io.github.dfa1.vortex.reader.array.TestArrays.float16;
import static io.github.dfa1.vortex.reader.array.TestArrays.floats;
import static io.github.dfa1.vortex.reader.array.TestArrays.ints;
import static io.github.dfa1.vortex.reader.array.TestArrays.longs;
import static io.github.dfa1.vortex.reader.array.TestArrays.shorts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests the [Array#limited(long)] contract: the [Array#limited(Array, long)] guard
/// (no-op clamp + negative rejection) and every concrete implementation —
/// zero-copy views, composite child recursion, chunked prefix retention, and the
/// [UnknownArray] rejection.
class ArrayLimitedTest {

    @Nested
    class Guard {

        @Test
        void rowsEqualToLengthReturnsSameInstance() {
            // Given
            LongArray sut = longs(1L, 2L, 3L);

            // When
            Array result = Array.limited(sut, 3);

            // Then
            assertThat(result).isSameAs(sut);
        }

        @Test
        void rowsBiggerThanLengthReturnsSameInstance() {
            // Given — limit is min(length, rows): asking for more than exists is a no-op
            LongArray sut = longs(1L, 2L, 3L);

            // When
            Array result = Array.limited(sut, 99);

            // Then
            assertThat(result).isSameAs(sut);
        }

        @Test
        void negativeRowsThrows() {
            // Given
            LongArray sut = longs(1L);

            // When / Then
            assertThatThrownBy(() -> Array.limited(sut, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(">= 0");
        }
    }

    @Nested
    class Primitive {

        @Test
        void cutsToFirstRowsAsView() {
            // Given
            LongArray sut = longs(10L, 20L, 30L, 40L);

            // When
            Array result = sut.limited(2);

            // Then
            assertThat(result.length()).isEqualTo(2L);
            assertThat(((LongArray) result).getLong(0)).isEqualTo(10L);
            assertThat(((LongArray) result).getLong(1)).isEqualTo(20L);
        }

        @Test
        void float16SlicesBuffer() {
            // Given
            Float16Array sut = float16(1.0f, 2.0f, 3.0f);

            // When
            Array result = sut.limited(2);

            // Then
            assertThat(result.length()).isEqualTo(2L);
            assertThat(((Float16Array) result).getFloat(0)).isEqualTo(1.0f);
            assertThat(((Float16Array) result).getFloat(1)).isEqualTo(2.0f);
        }
    }

    @Nested
    class Composite {

        @Test
        void structLimitsEachField() {
            // Given
            DType.Struct dtype = new DType.Struct(List.of(ColumnName.of("a"), ColumnName.of("b")), List.of(I64, I64), false);
            StructArray sut = new StructArray(dtype, 3,
                    List.of(longs(1L, 2L, 3L), longs(10L, 20L, 30L)));

            // When
            StructArray result = (StructArray) sut.limited(2);

            // Then
            assertThat(result.length()).isEqualTo(2L);
            assertThat(result.field(0).length()).isEqualTo(2L);
            assertThat(((LongArray) result.field(1)).getLong(1)).isEqualTo(20L);
        }

        @Test
        void listLimitsOffsetsToRowsPlusOne() {
            // Given — 3 lists over offsets [0,2,3,5]; elements shared
            DType.List dtype = new DType.List(I64, false);
            LongArray elements = longs(7L, 7L, 8L, 9L, 9L);
            LongArray offsets = longs(0L, 2L, 3L, 5L);
            ListArray sut = new ListArray(dtype, 3, elements, offsets);

            // When
            ListArray result = (ListArray) sut.limited(2);

            // Then — offsets keep rows+1 = 3 entries so list[1] bounds stay readable
            assertThat(result.length()).isEqualTo(2L);
            assertThat(result.offsets().length()).isEqualTo(3L);
            assertThat(result.elements()).isSameAs(elements);
        }

        @Test
        void listViewLimitsOffsetsAndSizes() {
            // Given
            DType.List dtype = new DType.List(I64, false);
            LongArray elements = longs(1L, 2L, 3L, 4L);
            LongArray offsets = longs(0L, 2L, 3L);
            LongArray sizes = longs(2L, 1L, 1L);
            ListViewArray sut = new ListViewArray(dtype, 3, elements, offsets, sizes);

            // When
            ListViewArray result = (ListViewArray) sut.limited(2);

            // Then
            assertThat(result.length()).isEqualTo(2L);
            assertThat(result.offsets().length()).isEqualTo(2L);
            assertThat(result.sizes().length()).isEqualTo(2L);
        }

        @Test
        void fixedSizeListLimitsElementsByWidth() {
            // Given — fixedSize 2: 3 rows -> 6 elements
            DType.FixedSizeList dtype = new DType.FixedSizeList(I64, 2, false);
            FixedSizeListArray sut = new FixedSizeListArray(dtype, 3,
                    longs(1L, 2L, 3L, 4L, 5L, 6L));

            // When
            FixedSizeListArray result = (FixedSizeListArray) sut.limited(2);

            // Then — 2 rows -> 4 elements
            assertThat(result.length()).isEqualTo(2L);
            assertThat(result.elements().length()).isEqualTo(4L);
        }

        @Test
        void variantLimitsCoreAndShredded() {
            // Given
            VariantArray sut = new VariantArray(I64, 3, longs(1L, 2L, 3L), longs(4L, 5L, 6L));

            // When
            VariantArray result = (VariantArray) sut.limited(2);

            // Then
            assertThat(result.length()).isEqualTo(2L);
            assertThat(result.coreStorage().length()).isEqualTo(2L);
            assertThat(result.shredded().length()).isEqualTo(2L);
        }

        @Test
        void variantWithNullShreddedStaysNull() {
            // Given
            VariantArray sut = new VariantArray(I64, 3, longs(1L, 2L, 3L), null);

            // When
            VariantArray result = (VariantArray) sut.limited(2);

            // Then
            assertThat(result.shredded()).isNull();
        }
    }

    @Nested
    class Chunked {

        @Test
        void limitAcrossBoundaryKeepsPrefixAndCutsBoundaryChild() {
            // Given — two chunks [0,1,2][3,4]; limit 4 lands inside the second chunk
            ChunkedLongArray sut = ChunkedLongArray.of(I64, 5,
                    List.of(longs(0L, 1L, 2L), longs(3L, 4L)));

            // When
            Array result = sut.limited(4);

            // Then — first chunk kept whole, boundary chunk truncated to 1 row
            assertThat(result.length()).isEqualTo(4L);
            assertThat(((LongArray) result).getLong(0)).isZero();
            assertThat(((LongArray) result).getLong(3)).isEqualTo(3L);
        }

        @Test
        void limitWithinFirstChunkDropsLaterChunks() {
            // Given — two chunks; limit 2 falls inside the first
            ChunkedLongArray sut = ChunkedLongArray.of(I64, 5,
                    List.of(longs(0L, 1L, 2L), longs(3L, 4L)));

            // When
            Array result = sut.limited(2);

            // Then — only the (truncated) first chunk survives
            assertThat(result.length()).isEqualTo(2L);
            assertThat(((LongArray) result).getLong(1)).isEqualTo(1L);
        }

        @Test
        void intChunkedLimitsAcrossBoundary() {
            // Given
            ChunkedIntArray sut = ChunkedIntArray.of(I32, 4, List.of(ints(0, 1), ints(2, 3)));

            // When
            Array result = sut.limited(3);

            // Then
            assertThat(result.length()).isEqualTo(3L);
            assertThat(((IntArray) result).getInt(2)).isEqualTo(2);
        }

        @Test
        void doubleChunkedLimitsAcrossBoundary() {
            // Given
            ChunkedDoubleArray sut = ChunkedDoubleArray.of(F64, 4,
                    List.of(doubles(0.5, 1.5), doubles(2.5, 3.5)));

            // When
            Array result = sut.limited(3);

            // Then
            assertThat(result.length()).isEqualTo(3L);
            assertThat(((DoubleArray) result).getDouble(2)).isEqualTo(2.5);
        }

        @Test
        void floatChunkedLimitsAcrossBoundary() {
            // Given
            ChunkedFloatArray sut = ChunkedFloatArray.of(F32, 4,
                    List.of(floats(0.5f, 1.5f), floats(2.5f, 3.5f)));

            // When
            Array result = sut.limited(3);

            // Then
            assertThat(result.length()).isEqualTo(3L);
            assertThat(((FloatArray) result).getFloat(2)).isEqualTo(2.5f);
        }

        @Test
        void shortChunkedLimitsAcrossBoundary() {
            // Given
            ChunkedShortArray sut = ChunkedShortArray.of(I16, 4,
                    List.of(shorts((short) 0, (short) 1), shorts((short) 2, (short) 3)));

            // When
            Array result = sut.limited(3);

            // Then
            assertThat(result.length()).isEqualTo(3L);
            assertThat(((ShortArray) result).getShort(2)).isEqualTo((short) 2);
        }

        @Test
        void byteChunkedLimitsAcrossBoundary() {
            // Given
            ChunkedByteArray sut = ChunkedByteArray.of(I8, 4,
                    List.of(bytes((byte) 0, (byte) 1), bytes((byte) 2, (byte) 3)));

            // When
            Array result = sut.limited(3);

            // Then
            assertThat(result.length()).isEqualTo(3L);
            assertThat(((ByteArray) result).getByte(2)).isEqualTo((byte) 2);
        }

        @Test
        void boolChunkedLimitsAcrossBoundary() {
            // Given
            ChunkedBoolArray sut = ChunkedBoolArray.of(BOOL, 4,
                    List.of(bools(true, false), bools(true, true)));

            // When
            Array result = sut.limited(3);

            // Then
            assertThat(result.length()).isEqualTo(3L);
            assertThat(((BoolArray) result).getBoolean(2)).isTrue();
        }
    }

    @Nested
    class Unsupported {

        @Test
        void unknownArrayThrows() {
            // Given
            UnknownArray sut = new UnknownArray(EncodingId.parse("vortex.mystery"), I64, 3, null,
                    new MemorySegment[0], new Array[0]);

            // When / Then
            assertThatThrownBy(() -> sut.limited(1))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("vortex.mystery");
        }
    }
}
