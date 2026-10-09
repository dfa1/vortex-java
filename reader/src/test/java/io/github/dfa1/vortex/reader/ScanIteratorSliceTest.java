package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyConstantDecimalArray;
import io.github.dfa1.vortex.reader.array.LazyDecimalArray;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MapArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.UnionArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.util.List;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;
import static org.assertj.core.api.Assertions.assertThat;

/// [ScanIterator#sliceArray] cuts one decoded chunk into the scan windows it spans when columns
/// chunk on different boundaries. A type with no case there made the whole scan throw, which is
/// how every TPC-H compat fixture became unreadable (decimal columns); these pin the cases added
/// for it. The byte-parts decimal form is covered end to end against vortex-jni by
/// `RustWritesJavaReadsIntegrationTest#s3_fullScan_decimalColumnMatchesJni`.
class ScanIteratorSliceTest {

    private static final DType.Decimal DECIMAL = new DType.Decimal((byte) 10, (byte) 2, false);
    private static final DType.List LIST_OF_I64 = new DType.List(DType.I64, false);

    @Test
    void bufferBackedDecimal_slicesItsBufferToTheWindow() {
        // Given — unscaled 100, 200, 300, 400 at scale 2
        MemorySegment buf = longs(100, 200, 300, 400);
        var full = new LazyDecimalArray(DECIMAL, 4, buf, 8);

        // When
        var result = (DecimalArray) ScanIterator.sliceArray(full, 1, 2, DECIMAL);

        // Then
        assertThat(result.length()).isEqualTo(2);
        assertThat(result.getDecimal(0)).isEqualTo(new BigDecimal("2.00"));
        assertThat(result.getDecimal(1)).isEqualTo(new BigDecimal("3.00"));
    }

    @Test
    void constantDecimal_keepsItsValueAtTheWindowLength() {
        // Given
        var full = new LazyConstantDecimalArray(DECIMAL, 10, new BigDecimal("7.50"), 8);

        // When
        var result = (DecimalArray) ScanIterator.sliceArray(full, 4, 3, DECIMAL);

        // Then
        assertThat(result.length()).isEqualTo(3);
        assertThat(result.getDecimal(2)).isEqualTo(new BigDecimal("7.50"));
    }

    @Test
    void listView_slicesOffsetsAndSizesButSharesElements() {
        // Given — rows [1,2] [3,4] [5,6] as offsets/sizes into one elements array
        ListViewArray full = listView();

        // When — the window covering rows 1..2
        var result = (ListViewArray) ScanIterator.sliceArray(full, 1, 2, LIST_OF_I64);

        // Then — offsets stay absolute into the unchanged elements, so row 0 of the window is [3,4]
        assertThat(result.length()).isEqualTo(2);
        assertThat(result.elements()).isSameAs(full.elements());
        assertThat(((IntArray) result.offsets()).getInt(0)).isEqualTo(2);
        assertThat(((IntArray) result.offsets()).getInt(1)).isEqualTo(4);
        assertThat(((IntArray) result.sizes()).getInt(0)).isEqualTo(2);
    }

    @Test
    void map_slicesItsEntries() {
        // Given — a map whose physical entries are the list view above
        var mapType = new DType.Map(DType.I64, DType.I64, false, false);
        var full = new MapArray(mapType, 3, listView());

        // When
        var result = (MapArray) ScanIterator.sliceArray(full, 1, 2, mapType);

        // Then
        assertThat(result.length()).isEqualTo(2);
        assertThat(result.entries().length()).isEqualTo(2);
        assertThat(((IntArray) ((ListViewArray) result.entries()).offsets()).getInt(0)).isEqualTo(2);
    }

    @Test
    void union_slicesTypeIdsAndEveryVariantToTheWindow() {
        // Given — a sparse union: type ids and both variants row-aligned, rows pick b, a, b, a
        var unionType = new DType.Union(List.of(ColumnName.of("a"), ColumnName.of("b")),
                List.of(DType.I64, DType.I64), List.of(0, 1), false);
        var u8 = new DType.Primitive(PType.U8, false);
        var full = new UnionArray(unionType, 4,
                new MaterializedByteArray(u8, 4, MemorySegment.ofArray(new byte[]{1, 0, 1, 0})),
                List.of(new MaterializedLongArray(DType.I64, 4, longs(10, 11, 12, 13)),
                        new MaterializedLongArray(DType.I64, 4, longs(20, 21, 22, 23))));

        // When
        var result = (UnionArray) ScanIterator.sliceArray(full, 1, 2, unionType);

        // Then — window rows 1..2 are a=11 then b=22
        assertThat(result.length()).isEqualTo(2);
        assertThat(((LongArray) result.variant(result.variantIndex(0))).getLong(0)).isEqualTo(11);
        assertThat(((LongArray) result.variant(result.variantIndex(1))).getLong(1)).isEqualTo(22);
    }

    private static ListViewArray listView() {
        var elements = new MaterializedLongArray(DType.I64, 6, longs(1, 2, 3, 4, 5, 6));
        var offsets = new MaterializedIntArray(new DType.Primitive(PType.I32, false), 3, ints(0, 2, 4));
        var sizes = new MaterializedIntArray(new DType.Primitive(PType.I32, false), 3, ints(2, 2, 2));
        return new ListViewArray(LIST_OF_I64, 3, elements, offsets, sizes);
    }

    private static MemorySegment longs(long... values) {
        MemorySegment seg = MemorySegment.ofArray(new long[values.length]);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(LE_LONG, i, values[i]);
        }
        return seg;
    }

    private static MemorySegment ints(int... values) {
        MemorySegment seg = MemorySegment.ofArray(new int[values.length]);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(LE_INT, i, values[i]);
        }
        return seg;
    }
}
