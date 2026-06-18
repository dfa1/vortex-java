package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.LongArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for [GridRender.formatCell]: per-type rendering plus the empty-cell
/// guard paths (null array, out-of-range index), exercised against in-memory
/// arrays without a terminal or fixture file.
class GridRenderTest {

    private static final DType I64 = new DType.Primitive(PType.I64, false);
    private static final DType F64 = new DType.Primitive(PType.F64, false);
    private static final DType BOOL = new DType.Bool(false);

    @Test
    void nullArrayRendersEmpty() {
        assertThat(GridRender.formatCell(null, 0, I64)).isEmpty();
    }

    @Test
    void outOfRangeIndexRendersEmpty() {
        try (Arena arena = Arena.ofConfined()) {
            LongArray a = ArrayFixtures.longs(arena, 1L, 2L);
            assertThat(GridRender.formatCell(a, 5, I64)).isEmpty();
        }
    }

    @Test
    void rendersNumericAndBoolTypes() {
        try (Arena arena = Arena.ofConfined()) {
            assertThat(GridRender.formatCell(ArrayFixtures.longs(arena, 7L), 0, I64)).isEqualTo("7");
            assertThat(GridRender.formatCell(ArrayFixtures.ints(arena, -3), 0, I64)).isEqualTo("-3");
            assertThat(GridRender.formatCell(ArrayFixtures.shorts(arena, (short) 4), 0, I64)).isEqualTo("4");
            assertThat(GridRender.formatCell(ArrayFixtures.bytes(arena, (byte) 8), 0, I64)).isEqualTo("8");
            assertThat(GridRender.formatCell(ArrayFixtures.doubles(arena, 0.25), 0, F64)).isEqualTo("0.25");
            assertThat(GridRender.formatCell(ArrayFixtures.floats(arena, 1.25f), 0, F64)).isEqualTo("1.25");
            assertThat(GridRender.formatCell(ArrayFixtures.bools(arena, true), 0, BOOL)).isEqualTo("true");
        }
    }
}
