package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkSliceTest {

    private static final ColumnName ID = ColumnName.of("id");
    private static final Arena ARENA = Arena.ofAuto();

    @Test
    void slice_viewsTheRowRange() {
        // Given
        Chunk sut = chunk(10);

        // When
        Chunk result = sut.slice(3, 4);

        // Then
        assertThat(result.rowCount()).isEqualTo(4);
        LongArray ids = result.column(ID);
        assertThat(ids.length()).isEqualTo(4);
        assertThat(ids.getLong(0)).isEqualTo(3);
        assertThat(ids.getLong(3)).isEqualTo(6);
    }

    @Test
    void closingTheSlice_leavesTheParentOpen() {
        // Given
        Chunk sut = chunk(10);
        Chunk slice = sut.slice(0, 5);

        // When
        slice.close();

        // Then — the shared arena belongs to the parent
        assertThat(sut.isClosed()).isFalse();
        assertThat(((LongArray) sut.column(ID)).getLong(9)).isEqualTo(9);
    }

    @ParameterizedTest
    @CsvSource({"-1, 1", "0, 11", "10, 1", "5, -1"})
    void slice_outsideTheChunk_throws(long offset, long length) {
        // Given
        Chunk sut = chunk(10);

        // When / Then
        assertThatThrownBy(() -> sut.slice(offset, length)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    private static Chunk chunk(int rows) {
        MemorySegment segment = ARENA.allocate(rows * 8L, 8);
        for (int i = 0; i < rows; i++) {
            segment.setAtIndex(VortexFormat.LE_LONG, i, i);
        }
        SequencedMap<ColumnName, Chunk.Column> columns = new LinkedHashMap<>();
        columns.put(ID, new Chunk.Column(new MaterializedLongArray(DType.I64, rows, segment), DType.I64));
        return new Chunk(rows, columns, ARENA, ReadRegistry.loadAll(), _ -> { });
    }
}
