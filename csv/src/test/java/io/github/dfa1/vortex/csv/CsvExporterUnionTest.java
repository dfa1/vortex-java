package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.UnionArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.stream.LongStream;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;
import static org.assertj.core.api.Assertions.assertThat;

/// No writer emits `vortex.union` (it is in no edition, and vortex-jni's Arrow bridge rejects
/// unions), so the union cell rendering is pinned on a hand-built [UnionArray].
class CsvExporterUnionTest {

    @Test
    void unionCell_rendersTheSelectedVariant_nullRowEmpty() {
        // Given — a nullable union of two i64 variants with type ids 3 and 9; row 1 is null
        var dtype = new DType.Union(List.of(ColumnName.of("a"), ColumnName.of("b")),
                List.of(DType.I64, DType.I64), List.of(3, 9), true);
        var typeIdValues = new MaterializedByteArray(new DType.Primitive(PType.U8, false), 3,
                MemorySegment.ofArray(new byte[]{9, 0, 3}));
        BoolArray validity = new MaterializedBoolArray(DType.BOOL, 3, MemorySegment.ofArray(new byte[]{0b101}));
        var sut = new UnionArray(dtype, 3, new MaskedArray(typeIdValues, validity),
                List.of(longs(10, 11, 12), longs(20, 21, 22)));

        // When
        List<String> result = LongStream.range(0, 3).mapToObj(row -> CsvExporter.cellValue(sut, row)).toList();

        // Then
        assertThat(result).containsExactly("20", "", "12");
    }

    private static MaterializedLongArray longs(long... values) {
        MemorySegment seg = MemorySegment.ofArray(new long[values.length]);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(LE_LONG, i, values[i]);
        }
        return new MaterializedLongArray(DType.I64, values.length, seg);
    }
}
