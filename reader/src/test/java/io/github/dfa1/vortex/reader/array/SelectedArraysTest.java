package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A filtered scan returns only the rows that match through these selections. Each family must
/// read back exactly the selected rows, in order, from flat buffers (so the unfiltered scan's fast
/// paths apply); a dictionary must stay a dictionary, or the compute kernels' dictionary lanes (and
/// Calcite's grouped push-down) decline the selection.
class SelectedArraysTest {

    private static final Arena ARENA = Arena.ofAuto();
    /// Skips row 2 between selected rows, so a copy that ignored the bitmap, or stopped at the
    /// first gap, would show.
    private static final int[] ROWS = rows(0, 1, 3);

    @Test
    void primitives_copyTheSelectedRowsIntoFlatBuffers() {
        // When
        LongArray longs = (LongArray) select(TestArrays.longs(10, 11, 12, 13), ROWS, ARENA);
        IntArray ints = (IntArray) select(TestArrays.ints(10, 11, 12, 13), ROWS, ARENA);
        ShortArray shorts = (ShortArray) select(
                TestArrays.shorts((short) 10, (short) 11, (short) 12, (short) 13), ROWS, ARENA);
        ByteArray bytes = (ByteArray) select(
                TestArrays.bytes((byte) 10, (byte) 11, (byte) 12, (byte) 13), ROWS, ARENA);
        DoubleArray doubles = (DoubleArray) select(TestArrays.doubles(1.5, 2.5, 3.5, 4.5), ROWS, ARENA);
        FloatArray floats = (FloatArray) select(TestArrays.floats(1.5f, 2.5f, 3.5f, 4.5f), ROWS, ARENA);
        Float16Array halves = (Float16Array) select(TestArrays.float16(1.5f, 2.5f, 3.5f, 4.5f), ROWS, ARENA);
        BoolArray bools = (BoolArray) select(TestArrays.bools(false, true, false, true), ROWS, ARENA);

        // Then
        assertThat(longs.length()).isEqualTo(3);
        assertThat(List.of(longs.getLong(0), longs.getLong(1), longs.getLong(2))).isEqualTo(List.of(10L, 11L, 13L));
        assertThat(List.of(ints.getInt(0), ints.getInt(1), ints.getInt(2))).isEqualTo(List.of(10, 11, 13));
        assertThat(List.of(shorts.getShort(0), shorts.getShort(2))).isEqualTo(List.of((short) 10, (short) 13));
        assertThat(shorts.fold(0, Long::sum)).isEqualTo(10 + 11 + 13);
        assertThat(List.of(bytes.getByte(0), bytes.getByte(2))).isEqualTo(List.of((byte) 10, (byte) 13));
        assertThat(bytes.fold(0, Long::sum)).isEqualTo(10 + 11 + 13);
        assertThat(List.of(doubles.getDouble(1), doubles.getDouble(2))).isEqualTo(List.of(2.5, 4.5));
        assertThat(List.of(floats.getFloat(1), floats.getFloat(2))).isEqualTo(List.of(2.5f, 4.5f));
        assertThat(List.of(halves.getFloat(1), halves.getFloat(2))).isEqualTo(List.of(2.5f, 4.5f));
        assertThat(List.of(bools.getBoolean(0), bools.getBoolean(1), bools.getBoolean(2)))
                .isEqualTo(List.of(false, true, true));
        assertThat(longs.dtype()).isEqualTo(DType.I64);
        assertThat(List.of(longs, ints, shorts, bytes, doubles, floats, halves, bools))
                .allMatch(a -> a.segmentIfPresent().isPresent());
    }

    @Test
    void sparseSelection_gathersEachRowAtEveryWidth() {
        // Given: three of ten rows, density under one half, so the flat copy takes the typed
        // per-row gather instead of the run copy (ROWS above takes the run copy)
        int[] sparse = rows(0, 5, 9);

        // When
        LongArray longs = (LongArray) select(TestArrays.longs(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), sparse, ARENA);
        IntArray ints = (IntArray) select(TestArrays.ints(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), sparse, ARENA);
        ShortArray shorts = (ShortArray) select(TestArrays.shorts(
                (short) 0, (short) 1, (short) 2, (short) 3, (short) 4, (short) 5, (short) 6, (short) 7, (short) 8, (short) 9),
                sparse, ARENA);
        ByteArray bytes = (ByteArray) select(TestArrays.bytes(
                (byte) 0, (byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5, (byte) 6, (byte) 7, (byte) 8, (byte) 9),
                sparse, ARENA);

        // Then
        assertThat(List.of(longs.getLong(0), longs.getLong(1), longs.getLong(2))).isEqualTo(List.of(0L, 5L, 9L));
        assertThat(List.of(ints.getInt(0), ints.getInt(1), ints.getInt(2))).isEqualTo(List.of(0, 5, 9));
        assertThat(List.of(shorts.getShort(0), shorts.getShort(1), shorts.getShort(2)))
                .isEqualTo(List.of((short) 0, (short) 5, (short) 9));
        assertThat(List.of(bytes.getByte(0), bytes.getByte(1), bytes.getByte(2)))
                .isEqualTo(List.of((byte) 0, (byte) 5, (byte) 9));
    }

    @Test
    void broadcastBuffer_copiesRowByRowNotByRuns() {
        // Given: a constant's one-element buffer standing for four rows; a run copy of rows 1..3
        // would read past the buffer's single element
        MemorySegment one = ARENA.allocate(8, 8);
        one.set(ValueLayout.JAVA_LONG, 0, 42L);
        Array constant = new MaterializedLongArray(DType.I64, 4, one);

        // When
        LongArray result = (LongArray) select(constant, rows(1, 2, 3), ARENA);

        // Then
        assertThat(List.of(result.getLong(0), result.getLong(1), result.getLong(2))).isEqualTo(List.of(42L, 42L, 42L));
    }

    @Test
    void bools_packEverySelectedBitAcrossBytes() {
        // Given: twenty rows, every third true, so the selection spans three output bytes
        boolean[] values = new boolean[20];
        int[] selected = new int[19];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 3 == 0;
            if (i != 7) {
                selected[i < 7 ? i : i - 1] = i;
            }
        }

        // When
        BoolArray result = (BoolArray) select(TestArrays.bools(values), selected, ARENA);

        // Then
        assertThat(result.length()).isEqualTo(19);
        for (int k = 0; k < 19; k++) {
            int row = k < 7 ? k : k + 1;
            assertThat(result.getBoolean(k)).as("row %d", row).isEqualTo(row % 3 == 0);
        }
    }

    @Test
    void materialize_writesTheSelectedRowsInOrder() {
        // Given
        Array selected = select(TestArrays.longs(10, 11, 12, 13), ROWS, ARENA);

        // When
        MemorySegment result = selected.materialize(ARENA);

        // Then
        assertThat(result.asSlice(0, 24).toArray(ValueLayout.JAVA_LONG)).containsExactly(10, 11, 13);
    }

    @Test
    void float16Materialize_keepsTheHalfBits() {
        // Given
        Float16Array selected = (Float16Array) select(TestArrays.float16(1.5f, 2.5f, 3.5f), rows(0, 2), ARENA);

        // When
        MemorySegment result = selected.materialize(ARENA);

        // Then
        assertThat(Float.float16ToFloat(result.getAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, 0))).isEqualTo(1.5f);
        assertThat(Float.float16ToFloat(result.getAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, 1))).isEqualTo(3.5f);
    }

    @Test
    void masked_selectsValuesAndValidityTogether() {
        // Given: row 1 is null
        Array masked = new MaskedArray(TestArrays.longs(10, 11, 12, 13), TestArrays.bools(true, false, true, true));

        // When
        MaskedArray result = (MaskedArray) select(masked, rows(1, 2), ARENA);

        // Then
        assertThat(result.isValid(0)).isFalse();
        assertThat(result.isValid(1)).isTrue();
        assertThat(((LongArray) result.inner()).getLong(1)).isEqualTo(12);
    }

    @Test
    void strings_copyTheSelectedRows() {
        // When
        VarBinArray result = (VarBinArray) select(strings("a", "bb", "ccc", "dddd"), ROWS, ARENA);

        // Then
        assertThat(List.of(result.getString(0), result.getString(1), result.getString(2)))
                .isEqualTo(List.of("a", "bb", "dddd"));
        assertThat(result.getByteLength(2)).isEqualTo(4);
        assertThat(result.getBytes(1)).isEqualTo("bb".getBytes(StandardCharsets.UTF_8));
        assertThat(result.compareUnsigned(0, "a".getBytes(StandardCharsets.UTF_8))).isZero();
        List<Integer> lengths = new ArrayList<>();
        result.forEachByteLength(lengths::add);
        assertThat(lengths).isEqualTo(List.of(1, 2, 4));
        assertThat(result.limited(2).length()).isEqualTo(2);
    }

    @Test
    void stringDictionary_staysADictionaryOverTheSamePool() {
        // Given: two values, four rows coded 1, 0, 1, 0
        Array dict = stringDict(new String[]{"x", "y"}, new byte[]{1, 0, 1, 0});

        // When
        Array result = select(dict, rows(0, 1), ARENA);

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
        Array result = select(dict, rows(2, 3), ARENA);

        // Then
        assertThat(result).isInstanceOf(DictLongArray.class);
        DictLongArray selected = (DictLongArray) result;
        assertThat(selected.codes()).isInstanceOf(MaterializedByteArray.class);
        assertThat(List.of(selected.getLong(0), selected.getLong(1))).isEqualTo(List.of(200L, 100L));
    }

    @Test
    void offsetSlice_selectsFromTheArrayItSlicesSoADictionaryStaysOne() {
        // Given: a slice starting at row 1 of a four-row dictionary; keeping slice rows 0 and 2
        // reads dictionary rows 1 and 3 only if the slice offset is applied
        Array dict = DictLongArray.of(DType.I64, 4, TestArrays.longs(100, 200),
                codes((byte) 0, (byte) 1, (byte) 0, (byte) 1));
        Array slice = new OffsetLongArray(DType.I64, 3, (LongArray) dict, 1);

        // When
        Array result = select(slice, rows(0, 2), ARENA);

        // Then
        assertThat(result).isInstanceOf(DictLongArray.class);
        LongArray selected = (LongArray) result;
        assertThat(List.of(selected.getLong(0), selected.getLong(1))).isEqualTo(List.of(200L, 200L));
    }

    @Test
    void allTrueMask_returnsTheArrayItself() {
        // Given
        Array full = TestArrays.longs(1, 2, 3);

        // When
        Array result = SelectedArrays.select(full, new Mask.AllTrue(3), ARENA);

        // Then: no copy when every row is kept, as Rust's filter returns the array unchanged
        assertThat(result).isSameAs(full);
    }

    @Test
    void denseSelectionWithLongRuns_copiesEachRunInBulk() {
        // Given: 100 rows, all kept but rows 10 and 50: density 0.98, three runs averaging 32
        // rows, so the flat copy takes the run path; a run boundary off by one would show
        long[] values = new long[100];
        int[] kept = new int[98];
        int k = 0;
        for (int i = 0; i < values.length; i++) {
            values[i] = i;
            if (i != 10 && i != 50) {
                kept[k++] = i;
            }
        }

        // When
        LongArray result = (LongArray) select(TestArrays.longs(values), kept, ARENA);

        // Then
        assertThat(result.length()).isEqualTo(98);
        for (int j = 0; j < 98; j++) {
            assertThat(result.getLong(j)).as("kept row %d", j).isEqualTo(kept[j]);
        }
    }

    @Test
    void denseSelectionWithShortRuns_gathersEachRow() {
        // Given: every row but each third kept: density 2/3 but runs of two, under the average
        // run of eight a bulk copy needs, so the copy gathers row by row
        long[] values = new long[30];
        int[] kept = new int[20];
        int k = 0;
        for (int i = 0; i < values.length; i++) {
            values[i] = 100 + i;
            if (i % 3 != 0) {
                kept[k++] = i;
            }
        }

        // When
        LongArray result = (LongArray) select(TestArrays.longs(values), kept, ARENA);

        // Then
        for (int j = 0; j < 20; j++) {
            assertThat(result.getLong(j)).as("kept row %d", j).isEqualTo(100 + kept[j]);
        }
    }

    @Test
    void offsetSliceOfAFlatArray_copiesFromItsShiftedRows() {
        // Given: a slice starting at row 1, under a mask, so the slice offset has to reach the
        // flat copy of both the values and the validity
        Array values = new OffsetIntArray(DType.I32, 3, TestArrays.ints(10, 11, 12, 13), 1);
        BoolArray validity = new OffsetBoolArray(DType.BOOL, 3, TestArrays.bools(true, true, false, true), 1);
        Array masked = new MaskedArray(values, validity);

        // When
        MaskedArray result = (MaskedArray) select(masked, rows(1, 2), ARENA);

        // Then: slice rows 1 and 2 are rows 2 and 3 of the sliced arrays
        assertThat(((IntArray) result.inner()).getInt(1)).isEqualTo(13);
        assertThat(result.isValid(0)).isFalse();
        assertThat(result.isValid(1)).isTrue();
    }

    @Test
    void struct_selectsEveryField() {
        // Given
        DType.Struct dtype = DType.structBuilder().field(ColumnName.of("a"), DType.I64).field(ColumnName.of("b"), DType.UTF8).build();
        Array struct = new StructArray(dtype, 3, List.of(TestArrays.longs(1, 2, 3), strings("p", "q", "r")));

        // When
        StructArray result = (StructArray) select(struct, rows(0, 2), ARENA);

        // Then
        assertThat(result.length()).isEqualTo(2);
        assertThat(((LongArray) result.field(0)).getLong(1)).isEqualTo(3);
        assertThat(((VarBinArray) result.field(1)).getString(1)).isEqualTo("r");
    }

    @Test
    void fixedSizeListOfNoElements_keepsTheSelectedRowCount() {
        // Given: three rows of zero elements each, which Rust accepts; the element mask keeps
        // nothing, so a copy that assumed some kept elements would fail
        DType.FixedSizeList dtype = new DType.FixedSizeList(DType.I64, 0, false);
        Array list = new FixedSizeListArray(dtype, 3, TestArrays.longs());

        // When
        FixedSizeListArray result = (FixedSizeListArray) select(list, rows(0, 2), ARENA);

        // Then
        assertThat(result.length()).isEqualTo(2);
        assertThat(result.elements().length()).isZero();
    }

    @Test
    void fixedSizeList_selectsEachRowsElements() {
        // Given: three rows of two elements: [1,2] [3,4] [5,6]
        DType.FixedSizeList dtype = new DType.FixedSizeList(DType.I64, 2, false);
        Array list = new FixedSizeListArray(dtype, 3, TestArrays.longs(1, 2, 3, 4, 5, 6));

        // When
        FixedSizeListArray result = (FixedSizeListArray) select(list, rows(0, 2), ARENA);

        // Then
        LongArray elements = (LongArray) result.elements();
        assertThat(elements.length()).isEqualTo(4);
        assertThat(List.of(elements.getLong(0), elements.getLong(1), elements.getLong(2), elements.getLong(3)))
                .isEqualTo(List.of(1L, 2L, 5L, 6L));
    }

    @Test
    void list_becomesAListViewOverTheSameElements() {
        // Given: rows [10] [20, 21] [30, 31, 32], offsets 0, 1, 3, 6
        DType.List dtype = new DType.List(DType.I64, false);
        Array list = new ListArray(dtype, 3, TestArrays.longs(10, 20, 21, 30, 31, 32), TestArrays.ints(0, 1, 3, 6));

        // When
        ListViewArray result = (ListViewArray) select(list, rows(1, 2), ARENA);

        // Then: row 0 starts at element 1 and holds 2; row 1 starts at 3 and holds 3
        assertThat(((LongArray) result.offsets()).getLong(0)).isEqualTo(1);
        assertThat(((LongArray) result.sizes()).getLong(0)).isEqualTo(2);
        assertThat(((LongArray) result.offsets()).getLong(1)).isEqualTo(3);
        assertThat(((LongArray) result.sizes()).getLong(1)).isEqualTo(3);
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
        DecimalArray result = (DecimalArray) select(decimals, rows(0, 2), ARENA);

        // Then
        assertThat(result.getDecimal(0)).isEqualTo(new BigDecimal("1.00"));
        assertThat(result.getUnscaledLong(1)).isEqualTo(300);
        MemorySegment materialized = result.materialize(ARENA);
        assertThat(materialized.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0)).isEqualTo(100);
        assertThat(materialized.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1)).isEqualTo(300);
    }

    @Test
    void wideDecimal_signExtendsEachValueToSixteenBytes() {
        // Given: a 16-byte decimal (precision 38) holding -5, 7 and -2^100: the copy writes the
        // minimal big-endian bytes back as 16 little-endian ones, so a negative value whose
        // minimal form is one byte only survives if the sign is extended
        DType.Decimal dtype = new DType.Decimal((byte) 38, (byte) 0, false);
        BigInteger[] values = {BigInteger.valueOf(-5), BigInteger.valueOf(7), BigInteger.TWO.pow(100).negate()};
        MemorySegment buf = ARENA.allocate(16L * values.length, 16);
        for (int i = 0; i < values.length; i++) {
            byte[] be = values[i].toByteArray();
            for (int b = 0; b < 16; b++) {
                byte sign = (byte) (values[i].signum() < 0 ? -1 : 0);
                buf.set(ValueLayout.JAVA_BYTE, i * 16L + b, b < be.length ? be[be.length - 1 - b] : sign);
            }
        }
        Array decimals = new LazyDecimalArray(dtype, values.length, buf, 16);

        // When
        DecimalArray result = (DecimalArray) select(decimals, rows(0, 2), ARENA);

        // Then
        assertThat(result.getDecimal(0)).isEqualTo(new BigDecimal(values[0]));
        assertThat(result.getDecimal(1)).isEqualTo(new BigDecimal(values[2]));
    }

    @Test
    void nullArray_keepsItsTypeWithTheSelectedLength() {
        // When
        Array result = select(new NullArray(DType.I64.asNullable(), 10), ROWS, ARENA);

        // Then
        assertThat(result).isEqualTo(new NullArray(DType.I64.asNullable(), 3));
    }

    @Test
    void anEmptySelection_hasNoRows() {
        // When
        Array result = select(TestArrays.longs(1, 2, 3), new int[0], ARENA);

        // Then
        assertThat(result.length()).isZero();
    }

    @Test
    void anArrayWithoutRows_failsAsVortexException() {
        // Given: raw undecoded bytes have no rows to select
        Array unknown = new UnknownArray(EncodingId.parse("acme.unknown"), DType.BINARY, 2, null,
                new MemorySegment[0], new Array[0]);

        // When / Then
        assertThatThrownBy(() -> select(unknown, rows(0), ARENA))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("cannot select rows");
    }

    private static int[] rows(int... rows) {
        return rows;
    }

    /// Selects `rows` of `full` through the mask a filter would build from them.
    private static Array select(Array full, int[] rows, SegmentAllocator arena) {
        return SelectedArrays.select(full, Mask.Values.fromIndices((int) full.length(), rows), arena);
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
