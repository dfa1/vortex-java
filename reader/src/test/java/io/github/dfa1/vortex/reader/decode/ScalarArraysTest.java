package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.proto.ProtoListValue;
import io.github.dfa1.vortex.core.proto.ProtoNullValue;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.reader.array.MapArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScalarArraysTest {

    private static final ProtoScalarValue NULL = ProtoScalarValue.ofNullValue(ProtoNullValue.NULL_VALUE);

    @Test
    void listRowsKeepTheirItemsAndNullItems() {
        // Given — rows of different lengths, one empty, one item null: the offsets must follow
        // each row and the null item must mask only its element
        DType.List dtype = new DType.List(DTypes.I32.asNullable(), false);
        ProtoScalarValue[] rows = {list(i64(1), i64(2)), list(), list(NULL, i64(3))};

        // When
        Array result = ScalarArrays.build(Arena.ofAuto(), dtype, rows);

        // Then
        ListArray lists = (ListArray) result;
        assertThat(List.of(ScalarArrays.index(lists.offsets(), 0), ScalarArrays.index(lists.offsets(), 1),
                ScalarArrays.index(lists.offsets(), 2), ScalarArrays.index(lists.offsets(), 3)))
                .containsExactly(0L, 2L, 2L, 4L);
        MaskedArray elements = (MaskedArray) lists.elements();
        assertThat(elements.validity().getBoolean(2)).isFalse();
        assertThat(((IntArray) elements.inner()).getInt(3)).isEqualTo(3);
    }

    @Test
    void aNullRowIsMaskedOverZeros() {
        // Given — a fixed-size list's null row still takes its width of elements, as zeros
        DType.FixedSizeList dtype = new DType.FixedSizeList(DTypes.I32, 2, true);
        ProtoScalarValue[] rows = {list(i64(5), i64(6)), NULL};

        // When
        Array result = ScalarArrays.build(Arena.ofAuto(), dtype, rows);

        // Then
        MaskedArray masked = (MaskedArray) result;
        assertThat(masked.validity().getBoolean(0)).isTrue();
        assertThat(masked.validity().getBoolean(1)).isFalse();
        IntArray elements = (IntArray) ((FixedSizeListArray) masked.inner()).elements();
        assertThat(List.of(elements.getInt(0), elements.getInt(1), elements.getInt(2), elements.getInt(3)))
                .containsExactly(5, 6, 0, 0);
    }

    @Test
    void aNegativeDecimalIsSignExtendedToTheColumnWidth() {
        // Given — a 2-byte scalar (-2) in a precision-18 column, 8 bytes wide: zero-extending
        // would read 65534
        DType.Decimal dtype = new DType.Decimal((byte) 18, (byte) 2, false);
        ProtoScalarValue[] rows = {ProtoScalarValue.ofBytesValue(new byte[]{(byte) 0xFE, (byte) 0xFF})};

        // When
        Array result = ScalarArrays.build(Arena.ofAuto(), dtype, rows);

        // Then
        assertThat(((DecimalArray) result).getDecimal(0)).isEqualByComparingTo(new BigDecimal("-0.02"));
    }

    @Test
    void aStructRowHoldsItsFields() {
        // Given
        DType.Struct dtype = new DType.Struct(List.of(ColumnName.of("a"), ColumnName.of("b")),
                List.of(DTypes.I32, DTypes.UTF8), false);
        ProtoScalarValue[] rows = {list(i64(7), ProtoScalarValue.ofStringValue("x"))};

        // When
        Array result = ScalarArrays.build(Arena.ofAuto(), dtype, rows);

        // Then
        StructArray struct = (StructArray) result;
        assertThat(((IntArray) struct.field(0)).getInt(0)).isEqualTo(7);
        assertThat(((VarBinArray) struct.field(1)).getString(0)).isEqualTo("x");
    }

    @Test
    void scalarAtReadsBackWhatBuildWrote() {
        // Given — a map, so a list view of structs, with a null row: the reverse direction
        // sparse patches go through
        DType.Map dtype = new DType.Map(DTypes.UTF8, DTypes.I64, false, true);
        ProtoScalarValue entry = list(ProtoScalarValue.ofBytesValue("k".getBytes()), i64(9));
        Array map = ScalarArrays.build(Arena.ofAuto(), dtype, new ProtoScalarValue[]{list(entry), NULL});

        // When
        ProtoScalarValue first = ScalarArrays.scalarAt(map, 0);
        ProtoScalarValue second = ScalarArrays.scalarAt(map, 1);

        // Then
        assertThat(map).isInstanceOf(MaskedArray.class);
        assertThat(((MaskedArray) map).inner()).isInstanceOf(MapArray.class);
        ProtoScalarValue readEntry = first.list_value().values().getFirst();
        assertThat(readEntry.list_value().values().get(0).bytes_value()).isEqualTo("k".getBytes());
        assertThat(readEntry.list_value().values().get(1).int64_value()).isEqualTo(9L);
        assertThat(second.null_value()).isNotNull();
    }

    private static ProtoScalarValue i64(long v) {
        return ProtoScalarValue.ofInt64Value(v);
    }

    private static ProtoScalarValue list(ProtoScalarValue... items) {
        return ProtoScalarValue.ofListValue(new ProtoListValue(List.of(items)));
    }
}
