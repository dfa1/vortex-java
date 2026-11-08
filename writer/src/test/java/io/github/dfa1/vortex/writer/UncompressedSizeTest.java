package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimeUnitMetadata;
import io.github.dfa1.vortex.writer.encode.FixedSizeListData;
import io.github.dfa1.vortex.writer.encode.ListData;
import io.github.dfa1.vortex.writer.encode.ListViewData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Rust's `uncompressed_size_in_bytes` of one batch, rule by rule; `FileStatisticsInteropIntegrationTest`
/// checks the same sizes against vortex-jni.
class UncompressedSizeTest {

    @ParameterizedTest
    @CsvSource({
            "4, 4, 0", // every row valid: no validity
            "0, 4, 2", // no row valid: a ConstantArray(false)'s 2-byte scalar
            "3, 4, 1", // some valid: a packed bitmap
            "8, 9, 2",
            "0, 0, 0"})
    void validityBytes(long valid, long rows, long expected) {
        // Given / When
        long result = UncompressedSize.validityBytes(valid, rows);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @Nested
    class Flat {

        @Test
        void primitive_widthPerRow() {
            // Given / When
            long result = UncompressedSize.of(DTypes.I32, new int[5], 5);

            // Then
            assertThat(result).isEqualTo(20);
        }

        @Test
        void nullablePrimitive_addsItsBitmap() {
            // Given
            NullableData data = new NullableData(new long[3], new boolean[]{true, false, true});

            // When
            long result = UncompressedSize.of(DTypes.I64.asNullable(), data, 3);

            // Then
            assertThat(result).isEqualTo(3 * 8 + 1);
        }

        @Test
        void bool_packedBits() {
            // Given / When
            long result = UncompressedSize.of(DTypes.BOOL, new boolean[9], 9);

            // Then
            assertThat(result).isEqualTo(2);
        }

        @Test
        void decimal_widthOfItsPrecision() {
            // Given — precision 10 takes 8 bytes, whatever the values
            DType.Decimal dtype = DType.Decimal.of(10, 2, false);

            // When
            long result = UncompressedSize.of(dtype, new BigDecimal[]{BigDecimal.ONE, BigDecimal.TEN}, 2);

            // Then
            assertThat(result).isEqualTo(16);
        }

        @Test
        void utf8_viewsPlusValuesTooLongToInline() {
            // Given — 12 bytes inline, 13 do not; the null row's value is not counted
            String[] values = {"a".repeat(12), "b".repeat(13), "c".repeat(20)};
            NullableData data = new NullableData(values, new boolean[]{true, true, false});

            // When
            long result = UncompressedSize.of(DTypes.UTF8.asNullable(), data, 3);

            // Then
            assertThat(result).isEqualTo(3 * 16 + 13 + 1);
        }

        @Test
        void utf8_countsUtf8Bytes() {
            // Given — 7 two-byte characters: 14 bytes, too long to inline
            String[] values = {"é".repeat(7)};

            // When
            long result = UncompressedSize.of(DTypes.UTF8, values, 1);

            // Then
            assertThat(result).isEqualTo(16 + 14);
        }

        @Test
        void binary_viewsPlusValuesTooLongToInline() {
            // Given
            byte[][] values = {new byte[2], new byte[40]};

            // When
            long result = UncompressedSize.of(DTypes.BINARY, values, 2);

            // Then
            assertThat(result).isEqualTo(2 * 16 + 40);
        }

        @Test
        void null_isZero() {
            // Given / When
            long result = UncompressedSize.of(DTypes.NULL, new long[7], 7);

            // Then
            assertThat(result).isZero();
        }

        @Test
        void extension_isItsStorage() {
            // Given
            DType.Extension date = new DType.Extension(ExtensionId.VORTEX_DATE, DTypes.I32,
                    new TimeUnitMetadata(TimeUnit.Days), false);

            // When
            long result = UncompressedSize.of(date, new int[3], 3);

            // Then
            assertThat(result).isEqualTo(12);
        }
    }

    @Nested
    class Nesting {

        @Test
        void struct_sumsItsFieldsAndAddsItsValidity() {
            // Given
            DType.Struct dtype = new DType.Struct(List.of(ColumnName.of("a"), ColumnName.of("b")),
                    List.of(DTypes.I32, DTypes.I8), true);
            NullableData data = new NullableData(new StructData(List.of(new int[4], new byte[4])),
                    new boolean[]{true, false, true, true});

            // When
            long result = UncompressedSize.of(dtype, data, 4);

            // Then
            assertThat(result).isEqualTo(4 * 4 + 4 + 1);
        }

        @Test
        void list_elementsOfValidListsPlusViews() {
            // Given — a null list holding no element: the valid lists tile [0, 3)
            DType.List dtype = new DType.List(DTypes.I32, true);
            ListData lists = new ListData(new int[3], new long[]{0, 2, 2, 3}, 3);
            NullableData data = new NullableData(lists, new boolean[]{true, false, true});

            // When
            long result = UncompressedSize.of(dtype, data, 3);

            // Then — 3 elements, 16 bytes of offset and size per row, a bitmap
            assertThat(result).isEqualTo(3 * 4 + 3 * 16 + 1);
        }

        @Test
        void list_nullListsElementsAreNotCounted() {
            // Given — the null list spans elements [2, 5)
            DType.List dtype = new DType.List(DTypes.I32, true);
            ListData lists = new ListData(new int[6], new long[]{0, 2, 5, 6}, 3);
            NullableData data = new NullableData(lists, new boolean[]{true, false, true});

            // When
            long result = UncompressedSize.of(dtype, data, 3);

            // Then
            assertThat(result).isEqualTo(3 * 4 + 3 * 16 + 1);
        }

        @Test
        void list_ofStrings_sizesTheUsedElements() {
            // Given
            DType.List dtype = new DType.List(DTypes.UTF8, false);
            ListData lists = new ListData(new String[]{"short", "x".repeat(20), "y"}, new long[]{0, 2, 3}, 2);

            // When
            long result = UncompressedSize.of(dtype, lists, 2);

            // Then
            assertThat(result).isEqualTo(3 * 16 + 20 + 2 * 16);
        }

        @Test
        void list_noValidList_onlyItsViews() {
            // Given
            DType.List dtype = new DType.List(DTypes.I32, true);
            NullableData data = new NullableData(new ListData(new int[2], new long[]{0, 2}, 1), new boolean[]{false});

            // When
            long result = UncompressedSize.of(dtype, data, 1);

            // Then
            assertThat(result).isEqualTo(16 + 2);
        }

        @Test
        void listView_overlappingLists_countEachUse() {
            // Given — both lists use element 1: three uses of a two-element run
            DType.List dtype = new DType.List(DTypes.I64, false);
            ListViewData lists = new ListViewData(new long[2], new int[]{0, 1}, new int[]{2, 1}, 2);

            // When
            long result = UncompressedSize.of(dtype, lists, 2);

            // Then
            assertThat(result).isEqualTo(3 * 8 + 2 * 16);
        }

        @Test
        void listView_gapsAndOverlapsThatTile_sizeTheRunAsASlice() {
            // Given — [0, 2), [1, 2) and [3, 4) use 4 elements and span [0, 4): Rust slices the run
            DType.List dtype = new DType.List(DTypes.UTF8, false);
            String[] elements = {"a", "b", "c".repeat(30), "d"};
            ListViewData lists = new ListViewData(elements, new int[]{0, 1, 3}, new int[]{2, 1, 1}, 3);

            // When
            long result = UncompressedSize.of(dtype, lists, 3);

            // Then — the slice holds "ccc…", which no list uses
            assertThat(result).isEqualTo(4 * 16 + 30 + 3 * 16);
        }

        @Test
        void fixedSizeList_everyElementNullRowsToo() {
            // Given
            DType.FixedSizeList dtype = new DType.FixedSizeList(DTypes.I16, 3, true);
            NullableData data = new NullableData(new FixedSizeListData(new short[6], 2), new boolean[]{false, true});

            // When
            long result = UncompressedSize.of(dtype, data, 2);

            // Then
            assertThat(result).isEqualTo(6 * 2 + 1);
        }

        @Test
        void map_keysAndValuesOfValidEntries() {
            // Given
            DType.Map dtype = new DType.Map(DTypes.I32, DTypes.I64, false, false);
            ListViewData entries = new ListViewData(new StructData(List.of(new int[3], new long[3])),
                    new int[]{0, 2}, new int[]{2, 1}, 2);

            // When
            long result = UncompressedSize.of(dtype, entries, 2);

            // Then
            assertThat(result).isEqualTo(3 * (4 + 8) + 2 * 16);
        }
    }

    @Test
    void supports_notVariant() {
        // Given
        DType.Struct dtype = new DType.Struct(List.of(ColumnName.of("v")), List.of(DTypes.VARIANT), false);

        // When / Then
        assertThat(UncompressedSize.supports(dtype)).isFalse();
        assertThat(UncompressedSize.supports(DTypes.I64)).isTrue();
    }
}
