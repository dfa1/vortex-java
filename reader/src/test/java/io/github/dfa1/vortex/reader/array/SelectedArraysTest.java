package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A filtered scan returns only the rows that match through these selections. Each family must
/// read back exactly the selected rows, in order; a dictionary must stay a dictionary, or the
/// compute kernels' dictionary lanes (and Calcite's grouped push-down) decline the selection.
class SelectedArraysTest {

    private static final Arena ARENA = Arena.ofAuto();
    /// Out of order and repeating, so a view that ignored the index map or sorted it would show.
    private static final long[] ROWS = {3, 0, 3, 1};

    @Test
    void primitives_readTheSelectedRows() {
        // When
        LongArray longs = (LongArray) SelectedArrays.select(TestArrays.longs(10, 11, 12, 13), ROWS, ARENA);
        IntArray ints = (IntArray) SelectedArrays.select(TestArrays.ints(10, 11, 12, 13), ROWS, ARENA);
        ShortArray shorts = (ShortArray) SelectedArrays.select(
                TestArrays.shorts((short) 10, (short) 11, (short) 12, (short) 13), ROWS, ARENA);
        ByteArray bytes = (ByteArray) SelectedArrays.select(
                TestArrays.bytes((byte) 10, (byte) 11, (byte) 12, (byte) 13), ROWS, ARENA);
        DoubleArray doubles = (DoubleArray) SelectedArrays.select(TestArrays.doubles(1.5, 2.5, 3.5, 4.5), ROWS, ARENA);
        FloatArray floats = (FloatArray) SelectedArrays.select(TestArrays.floats(1.5f, 2.5f, 3.5f, 4.5f), ROWS, ARENA);
        Float16Array halves = (Float16Array) SelectedArrays.select(TestArrays.float16(1.5f, 2.5f, 3.5f, 4.5f), ROWS, ARENA);
        BoolArray bools = (BoolArray) SelectedArrays.select(TestArrays.bools(false, true, false, true), ROWS, ARENA);

        // Then
        assertThat(longs.length()).isEqualTo(4);
        assertThat(List.of(longs.getLong(0), longs.getLong(1), longs.getLong(2), longs.getLong(3)))
                .isEqualTo(List.of(13L, 10L, 13L, 11L));
        assertThat(List.of(ints.getInt(0), ints.getInt(1), ints.getInt(2), ints.getInt(3)))
                .isEqualTo(List.of(13, 10, 13, 11));
        assertThat(List.of(shorts.getShort(0), shorts.getShort(3))).isEqualTo(List.of((short) 13, (short) 11));
        assertThat(shorts.fold(0, Long::sum)).isEqualTo(13 + 10 + 13 + 11);
        assertThat(List.of(bytes.getByte(0), bytes.getByte(3))).isEqualTo(List.of((byte) 13, (byte) 11));
        assertThat(bytes.fold(0, Long::sum)).isEqualTo(13 + 10 + 13 + 11);
        assertThat(List.of(doubles.getDouble(0), doubles.getDouble(1))).isEqualTo(List.of(4.5, 1.5));
        assertThat(List.of(floats.getFloat(0), floats.getFloat(1))).isEqualTo(List.of(4.5f, 1.5f));
        assertThat(List.of(halves.getFloat(0), halves.getFloat(1))).isEqualTo(List.of(4.5f, 1.5f));
        assertThat(List.of(bools.getBoolean(0), bools.getBoolean(1), bools.getBoolean(3)))
                .isEqualTo(List.of(true, false, true));
        assertThat(longs.dtype()).isEqualTo(DType.I64);
    }

    @Test
    void materialize_writesTheSelectedRowsInOrder() {
        // Given
        Array selected = SelectedArrays.select(TestArrays.longs(10, 11, 12, 13), ROWS, ARENA);

        // When
        MemorySegment result = selected.materialize(ARENA);

        // Then
        assertThat(result.toArray(ValueLayout.JAVA_LONG)).containsExactly(13, 10, 13, 11);
    }

    @Test
    void float16Materialize_keepsTheHalfBits() {
        // Given
        Float16Array selected = (Float16Array) SelectedArrays.select(TestArrays.float16(1.5f, 2.5f), new long[]{1, 0}, ARENA);

        // When
        MemorySegment result = selected.materialize(ARENA);

        // Then
        assertThat(Float.float16ToFloat(result.getAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, 0))).isEqualTo(2.5f);
        assertThat(Float.float16ToFloat(result.getAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, 1))).isEqualTo(1.5f);
    }

    @Test
    void masked_selectsValuesAndValidityTogether() {
        // Given: row 1 is null
        Array masked = new MaskedArray(TestArrays.longs(10, 11, 12, 13), TestArrays.bools(true, false, true, true));

        // When
        MaskedArray result = (MaskedArray) SelectedArrays.select(masked, new long[]{1, 2}, ARENA);

        // Then
        assertThat(result.isValid(0)).isFalse();
        assertThat(result.isValid(1)).isTrue();
        assertThat(((LongArray) result.inner()).getLong(1)).isEqualTo(12);
    }

    @Test
    void strings_readTheSelectedRows() {
        // When
        VarBinArray result = (VarBinArray) SelectedArrays.select(strings("a", "bb", "ccc", "dddd"), ROWS, ARENA);

        // Then
        assertThat(List.of(result.getString(0), result.getString(1), result.getString(2), result.getString(3)))
                .isEqualTo(List.of("dddd", "a", "dddd", "bb"));
        assertThat(result.getByteLength(0)).isEqualTo(4);
        assertThat(result.getBytes(3)).isEqualTo("bb".getBytes(StandardCharsets.UTF_8));
        assertThat(result.compareUnsigned(1, "a".getBytes(StandardCharsets.UTF_8))).isZero();
        List<Integer> lengths = new ArrayList<>();
        result.forEachByteLength(lengths::add);
        assertThat(lengths).isEqualTo(List.of(4, 1, 4, 2));
        assertThat(result.limited(2).length()).isEqualTo(2);
    }

    @Test
    void stringDictionary_staysADictionaryOverTheSamePool() {
        // Given: two values, four rows coded 1, 0, 1, 0
        Array dict = stringDict(new String[]{"x", "y"}, new byte[]{1, 0, 1, 0});

        // When
        Array result = SelectedArrays.select(dict, new long[]{0, 1}, ARENA);

        // Then
        assertThat(result).isInstanceOf(VarBinDictArray.class);
        VarBinDictArray selected = (VarBinDictArray) result;
        assertThat(List.of(selected.getString(0), selected.getString(1))).isEqualTo(List.of("y", "x"));
        assertThat(selected.values().length()).isEqualTo(2);
    }

    @Test
    void primitiveDictionary_staysADictionaryWithFlatCodes() {
        // Given: codes in a chunked array, which the selection flattens for the code-scan lane
        LongArray pool = TestArrays.longs(100, 200);
        Array codes = ChunkedByteArray.of(DType.U8, 4, List.of(codes((byte) 0, (byte) 1), codes((byte) 1, (byte) 0)));
        Array dict = DictLongArray.of(DType.I64, 4, pool, codes);

        // When
        Array result = SelectedArrays.select(dict, new long[]{3, 2}, ARENA);

        // Then
        assertThat(result).isInstanceOf(DictLongArray.class);
        DictLongArray selected = (DictLongArray) result;
        assertThat(selected.codes()).isInstanceOf(MaterializedByteArray.class);
        assertThat(List.of(selected.getLong(0), selected.getLong(1))).isEqualTo(List.of(100L, 200L));
    }

    @Test
    void offsetSlice_selectsFromTheArrayItSlicesSoADictionaryStaysOne() {
        // Given: a slice starting at row 2 of a four-row dictionary
        Array dict = DictLongArray.of(DType.I64, 4, TestArrays.longs(100, 200),
                codes((byte) 0, (byte) 0, (byte) 1, (byte) 0));
        Array slice = new OffsetLongArray(DType.I64, 2, (LongArray) dict, 2);

        // When
        Array result = SelectedArrays.select(slice, new long[]{1, 0}, ARENA);

        // Then
        assertThat(result).isInstanceOf(DictLongArray.class);
        LongArray selected = (LongArray) result;
        assertThat(List.of(selected.getLong(0), selected.getLong(1))).isEqualTo(List.of(100L, 200L));
    }

    @Test
    void struct_selectsEveryField() {
        // Given
        DType.Struct dtype = DType.structBuilder().field(ColumnName.of("a"), DType.I64).field(ColumnName.of("b"), DType.UTF8).build();
        Array struct = new StructArray(dtype, 3, List.of(TestArrays.longs(1, 2, 3), strings("p", "q", "r")));

        // When
        StructArray result = (StructArray) SelectedArrays.select(struct, new long[]{2, 0}, ARENA);

        // Then
        assertThat(result.length()).isEqualTo(2);
        assertThat(((LongArray) result.field(0)).getLong(0)).isEqualTo(3);
        assertThat(((VarBinArray) result.field(1)).getString(1)).isEqualTo("p");
    }

    @Test
    void fixedSizeList_selectsEachRowsElements() {
        // Given: three rows of two elements: [1,2] [3,4] [5,6]
        DType.FixedSizeList dtype = new DType.FixedSizeList(DType.I64, 2, false);
        Array list = new FixedSizeListArray(dtype, 3, TestArrays.longs(1, 2, 3, 4, 5, 6));

        // When
        FixedSizeListArray result = (FixedSizeListArray) SelectedArrays.select(list, new long[]{2, 0}, ARENA);

        // Then
        LongArray elements = (LongArray) result.elements();
        assertThat(List.of(elements.getLong(0), elements.getLong(1), elements.getLong(2), elements.getLong(3)))
                .isEqualTo(List.of(5L, 6L, 1L, 2L));
    }

    @Test
    void list_becomesAListViewOverTheSameElements() {
        // Given: rows [10] [20, 21] [30, 31, 32], offsets 0, 1, 3, 6
        DType.List dtype = new DType.List(DType.I64, false);
        Array list = new ListArray(dtype, 3, TestArrays.longs(10, 20, 21, 30, 31, 32), TestArrays.ints(0, 1, 3, 6));

        // When
        ListViewArray result = (ListViewArray) SelectedArrays.select(list, new long[]{2, 1}, ARENA);

        // Then: row 0 starts at element 3 and holds 3; row 1 starts at 1 and holds 2
        assertThat(((LongArray) result.offsets()).getLong(0)).isEqualTo(3);
        assertThat(((LongArray) result.sizes()).getLong(0)).isEqualTo(3);
        assertThat(((LongArray) result.offsets()).getLong(1)).isEqualTo(1);
        assertThat(((LongArray) result.sizes()).getLong(1)).isEqualTo(2);
        assertThat(result.elements().length()).isEqualTo(6);
    }

    @Test
    void decimal_readsAndMaterializesTheSelectedRows() {
        // Given: a 4-byte decimal (precision 5) holding 1.00, 2.00, 3.00
        DType.Decimal dtype = new DType.Decimal((byte) 5, (byte) 2, false);
        MemorySegment buf = ARENA.allocate(12, 4);
        for (int i = 0; i < 3; i++) {
            buf.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, (i + 1) * 100);
        }
        Array decimals = new LazyDecimalArray(dtype, 3, buf, 4);

        // When
        DecimalArray result = (DecimalArray) SelectedArrays.select(decimals, new long[]{2, 0}, ARENA);

        // Then
        assertThat(result.getDecimal(0)).isEqualTo(new BigDecimal("3.00"));
        assertThat(result.getUnscaledLong(1)).isEqualTo(100);
        MemorySegment materialized = result.materialize(ARENA);
        assertThat(materialized.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0)).isEqualTo(300);
        assertThat(materialized.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1)).isEqualTo(100);
    }

    @Test
    void nullArray_keepsItsTypeWithTheSelectedLength() {
        // When
        Array result = SelectedArrays.select(new NullArray(DType.I64.asNullable(), 10), ROWS, ARENA);

        // Then
        assertThat(result).isEqualTo(new NullArray(DType.I64.asNullable(), 4));
    }

    @Test
    void anEmptySelection_hasNoRows() {
        // When
        Array result = SelectedArrays.select(TestArrays.longs(1, 2, 3), new long[0], ARENA);

        // Then
        assertThat(result.length()).isZero();
    }

    @Test
    void anArrayWithoutRows_failsAsVortexException() {
        // Given: raw undecoded bytes have no rows to select
        Array unknown = new UnknownArray(EncodingId.parse("acme.unknown"), DType.BINARY, 2, null,
                new MemorySegment[0], new Array[0]);

        // When / Then
        assertThatThrownBy(() -> SelectedArrays.select(unknown, new long[]{0}, ARENA))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("cannot select rows");
    }

    private static VarBinArray strings(String... values) {
        byte[] all = String.join("", values).getBytes(StandardCharsets.UTF_8);
        MemorySegment offsets = ARENA.allocate((values.length + 1) * 4L, 4);
        int at = 0;
        for (int i = 0; i < values.length; i++) {
            offsets.setAtIndex(ValueLayout.JAVA_INT, i, at);
            at += values[i].getBytes(StandardCharsets.UTF_8).length;
        }
        offsets.setAtIndex(ValueLayout.JAVA_INT, values.length, at);
        return new VarBinOffsetArray(DType.UTF8, values.length, MemorySegment.ofArray(all), offsets, PType.I32);
    }

    private static Array stringDict(String[] pool, byte[] codes) {
        VarBinOffsetArray values = (VarBinOffsetArray) strings(pool);
        return VarBinArray.ofDict(DType.UTF8, codes.length, values.bytesSegment(), values.offsetsSegment(),
                PType.I32, MemorySegment.ofArray(codes), PType.U8);
    }

    private static MaterializedByteArray codes(byte... codes) {
        MemorySegment seg = ARENA.allocate(Math.max(1, codes.length));
        for (int i = 0; i < codes.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, codes[i]);
        }
        return new MaterializedByteArray(DType.U8, codes.length, seg);
    }
}
