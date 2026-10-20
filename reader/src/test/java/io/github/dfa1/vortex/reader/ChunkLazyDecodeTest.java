package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A chunk decodes a column the first time it is read: a top-N over a wide table reads only its sort
/// key for most chunks, and decoding every projected column up front was over 40% of such a query.
class ChunkLazyDecodeTest {

    private static final ColumnName A = ColumnName.of("a");
    private static final ColumnName B = ColumnName.of("b");
    private static final List<ColumnName> NAMES = List.of(A, B);
    private static final int ROWS = 10;

    private final Arena arena = Arena.ofAuto();
    private final List<Integer> decodes = new ArrayList<>();

    @Test
    void column_decodesOnlyTheColumnRead_once() {
        // Given
        Chunk sut = chunk();

        // When
        LongArray first = sut.column(A);
        LongArray result = sut.column(A);

        // Then
        assertThat(result).isSameAs(first);
        assertThat(decodes).containsExactly(0);
    }

    @Test
    void columns_decodesEveryColumn_inProjectionOrder() {
        // Given
        Chunk sut = chunk();
        sut.column(B);

        // When
        var result = sut.columns();

        // Then
        assertThat(result).containsOnlyKeys(A, B);
        assertThat(result.firstEntry().getKey()).isEqualTo(A);
        assertThat(decodes).containsExactly(1, 0);
    }

    @Test
    void slice_decodesTheParentColumnOnlyWhenTheSliceReadsIt() {
        // Given
        Chunk sut = chunk();
        Chunk slice = sut.slice(2, 3);
        assertThat(decodes).isEmpty();

        // When
        LongArray result = slice.column(B);

        // Then
        assertThat(result.getLong(0)).isEqualTo(102);
        assertThat(decodes).containsExactly(1);
    }

    @Test
    void column_afterClose_throwsInsteadOfDecodingIntoAClosedArena() {
        // Given
        Chunk sut = chunk();
        sut.close();

        // When / Then
        assertThatThrownBy(() -> sut.column(A)).isInstanceOf(IllegalStateException.class);
        assertThat(decodes).isEmpty();
    }

    @Test
    void column_unknownName_throwsWithoutDecoding() {
        // Given
        Chunk sut = chunk();
        ColumnName unknown = ColumnName.of("c");

        // When / Then
        assertThatThrownBy(() -> sut.column(unknown))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("unknown column");
        assertThat(decodes).isEmpty();
    }

    /// Column `a` holds `0..9`, column `b` holds `100..109`; every decode is recorded by index.
    private Chunk chunk() {
        return new Chunk(ROWS, NAMES, Chunk.positions(NAMES), i -> {
            decodes.add(i);
            MemorySegment segment = arena.allocate(ROWS * 8L, 8);
            for (int r = 0; r < ROWS; r++) {
                segment.setAtIndex(VortexFormat.LE_LONG, r, i * 100L + r);
            }
            return new Chunk.Column(new MaterializedLongArray(DType.I64, ROWS, segment), DType.I64);
        }, Arena.ofConfined(), ReadRegistry.loadAll(), _ -> { });
    }
}
