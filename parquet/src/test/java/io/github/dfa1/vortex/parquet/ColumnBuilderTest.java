package io.github.dfa1.vortex.parquet;

import dev.hardwood.row.PqList;
import dev.hardwood.row.PqStruct;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.encode.ListData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ColumnBuilderTest {

    @Nested
    class Leaf {

        @Test
        void nullablePrimitive_wrapsInNullableData_withDefaultsAtNullPositions() {
            // Given — Raincloud-style nullable int leaf; Hardwood decodes INT32 as Integer
            ColumnBuilder sut = ColumnBuilder.forType(new DType.Primitive(PType.I32, true));

            // When
            sut.append(1);
            sut.append(null);
            sut.append(3);
            Object result = sut.build();

            // Then
            assertThat(result).isInstanceOf(NullableData.class);
            NullableData data = (NullableData) result;
            assertThat((int[]) data.values()).containsExactly(1, 0, 3);
            assertThat(data.validity()).containsExactly(true, false, true);
        }

        @Test
        void nonNullablePrimitive_returnsPlainArray_notWrapped() {
            // Given
            ColumnBuilder sut = ColumnBuilder.forType(new DType.Primitive(PType.I32, false));

            // When
            sut.append(1);
            sut.append(2);
            Object result = sut.build();

            // Then
            assertThat(result).isInstanceOf(int[].class);
            assertThat((int[]) result).containsExactly(1, 2);
        }

        @Test
        void nullableUtf8_preservesRealNullsInStringArray() {
            // Given — the exact fix for the pre-existing null-placeholder bug: a null leaf must
            // stay null (not become ""), so ChunkImpl/StructEncodingEncoder can tell a null value
            // apart from an empty string.
            ColumnBuilder sut = ColumnBuilder.forType(new DType.Utf8(true));

            // When
            sut.append("a");
            sut.append(null);
            sut.append("b");
            Object result = sut.build();

            // Then
            assertThat(result).isInstanceOf(NullableData.class);
            NullableData data = (NullableData) result;
            assertThat((String[]) data.values()).containsExactly("a", null, "b");
            assertThat(data.validity()).containsExactly(true, false, true);
        }

        @Test
        void nullableBinary_roundTripsRawBytesAndNulls() {
            // Given — Raincloud's waxal-dagbani-asr-test "audio.bytes" (raw BYTE_ARRAY, no
            // logical-type annotation)
            ColumnBuilder sut = ColumnBuilder.forType(new DType.Binary(true));
            byte[] blob = {(byte) 0x80, 0x01};

            // When
            sut.append(blob);
            sut.append(null);
            Object result = sut.build();

            // Then
            assertThat(result).isInstanceOf(NullableData.class);
            NullableData data = (NullableData) result;
            byte[][] values = (byte[][]) data.values();
            assertThat(values[0]).isEqualTo(blob);
            assertThat(values[1]).isNull();
            assertThat(data.validity()).containsExactly(true, false);
        }
    }

    @Nested
    class Struct {

        @Mock
        private PqStruct struct;

        @Test
        void nullableStruct_recursesIntoFields_andWrapsWholeStructOnNullRow() {
            // Given — Raincloud's waxal-dagbani-asr-test "audio: STRUCT{bytes, path}", both
            // fields nullable; one row present, one row where the whole struct is absent
            DType.Struct dtype = new DType.Struct(
                    List.of(ColumnName.of("bytes"), ColumnName.of("path")),
                    List.of(new DType.Binary(true), new DType.Utf8(true)),
                    true);
            ColumnBuilder sut = ColumnBuilder.forType(dtype);
            byte[] blob = {0x01};
            given(struct.getValue(0)).willReturn(blob);
            given(struct.getValue(1)).willReturn("clip.wav");

            // When
            sut.append(struct);
            sut.append(null);
            Object result = sut.build();

            // Then — outer NullableData marks row 1 invalid; field arrays still carry a
            // placeholder at that position (masked out by the outer validity bit)
            assertThat(result).isInstanceOf(NullableData.class);
            NullableData outer = (NullableData) result;
            assertThat(outer.validity()).containsExactly(true, false);
            StructData fields = (StructData) outer.values();
            NullableData bytesField = (NullableData) fields.fieldArrays().get(0);
            assertThat(((byte[][]) bytesField.values())[0]).isEqualTo(blob);
            assertThat(bytesField.validity()).containsExactly(true, false);
            NullableData pathField = (NullableData) fields.fieldArrays().get(1);
            assertThat((String[]) pathField.values()).containsExactly("clip.wav", null);
        }
    }

    @Nested
    class ListOf {

        @Mock
        private PqList list1;
        @Mock
        private PqList list2;

        @Test
        void nullableList_distinguishesNullFromEmpty() {
            // Given — Raincloud's finetranslations-swedish "og_chunks: LIST<string>"; row 0 has
            // two elements, row 1 is a genuinely null list (not an empty one), row 2 has one
            given(list1.values()).willReturn(List.of("a", "b"));
            given(list2.values()).willReturn(List.of("c"));
            ColumnBuilder sut = ColumnBuilder.forType(new DType.List(new DType.Utf8(false), true));

            // When
            sut.append(list1);
            sut.append(null);
            sut.append(list2);
            Object result = sut.build();

            // Then
            assertThat(result).isInstanceOf(NullableData.class);
            NullableData outer = (NullableData) result;
            assertThat(outer.validity()).containsExactly(true, false, true);
            ListData listData = (ListData) outer.values();
            assertThat((String[]) listData.elements()).containsExactly("a", "b", "c");
            assertThat(listData.offsets()).containsExactly(0L, 2L, 2L, 3L);
            assertThat(listData.outerLen()).isEqualTo(3L);
        }

        @Test
        void listOfNullableStruct_propagatesElementLevelNulls() {
            // Given — Raincloud's ultrachat-200k "messages: LIST<STRUCT{content, role}>" where
            // the element struct itself is OPTIONAL (a list can contain a null struct)
            PqStruct element = org.mockito.Mockito.mock(PqStruct.class);
            given(element.getValue(0)).willReturn("hi");
            given(list1.values()).willReturn(java.util.Arrays.asList(element, null));
            DType.Struct elementType = new DType.Struct(
                    List.of(ColumnName.of("content")), List.of(new DType.Utf8(true)), true);
            ColumnBuilder sut = ColumnBuilder.forType(new DType.List(elementType, false));

            // When
            sut.append(list1);
            Object result = sut.build();

            // Then — non-nullable list itself: plain ListData, but its elements (nullable
            // struct) must still be NullableData so the null element survives
            ListData listData = (ListData) result;
            assertThat(listData.outerLen()).isEqualTo(1L);
            NullableData elements = (NullableData) listData.elements();
            assertThat(elements.validity()).containsExactly(true, false);
            StructData structData = (StructData) elements.values();
            NullableData contentField = (NullableData) structData.fieldArrays().get(0);
            assertThat((String[]) contentField.values()).containsExactly("hi", null);
        }
    }
}
