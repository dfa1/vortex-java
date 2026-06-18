package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyConstantDecimalArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.math.BigDecimal;
import java.util.List;

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
        // Given / When / Then — a null column array renders as an empty cell
        assertThat(GridRender.formatCell(null, 0, I64)).isEmpty();
    }

    @Test
    void outOfRangeIndexRendersEmpty() {
        try (Arena arena = Arena.ofConfined()) {
            // Given
            LongArray sut = ArrayFixtures.longs(arena, 1L, 2L);

            // When / Then — index past length renders empty, not OOB
            assertThat(GridRender.formatCell(sut, 5, I64)).isEmpty();
        }
    }

    @Test
    void rendersNumericAndBoolTypes() {
        try (Arena arena = Arena.ofConfined()) {
            // Given / When / Then — each primitive family formats via its accessor
            assertThat(GridRender.formatCell(ArrayFixtures.longs(arena, 7L), 0, I64)).isEqualTo("7");
            assertThat(GridRender.formatCell(ArrayFixtures.ints(arena, -3), 0, I64)).isEqualTo("-3");
            assertThat(GridRender.formatCell(ArrayFixtures.shorts(arena, (short) 4), 0, I64)).isEqualTo("4");
            assertThat(GridRender.formatCell(ArrayFixtures.bytes(arena, (byte) 8), 0, I64)).isEqualTo("8");
            assertThat(GridRender.formatCell(ArrayFixtures.doubles(arena, 0.25), 0, F64)).isEqualTo("0.25");
            assertThat(GridRender.formatCell(ArrayFixtures.floats(arena, 1.25f), 0, F64)).isEqualTo("1.25");
            assertThat(GridRender.formatCell(ArrayFixtures.bools(arena, true), 0, BOOL)).isEqualTo("true");
        }
    }

    @Test
    void rendersDecimal() {
        // Given
        DType decimal = new DType.Decimal((byte) 10, (byte) 2, false);
        Array sut = new LazyConstantDecimalArray(decimal, 3, new BigDecimal("1.23"), 8);

        // When / Then
        assertThat(GridRender.formatCell(sut, 0, decimal)).isEqualTo("1.23");
    }

    @Test
    void rendersDateExtensionFromIntStorage() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — vortex.date over I32 epoch-day storage: day 0 = 1970-01-01
            DType dateExt = new DType.Extension("vortex.date",
                    new DType.Primitive(PType.I32, false), null, false);
            IntArray storage = ArrayFixtures.ints(arena, 0, 1);

            // When / Then
            assertThat(GridRender.formatCell(storage, 0, dateExt)).isEqualTo("1970-01-01");
            assertThat(GridRender.formatCell(storage, 1, dateExt)).isEqualTo("1970-01-02");
        }
    }

    @Test
    void maskedNullCellRendersEmpty() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — validity [true, false]: row 1 is null
            MaskedArray sut = new MaskedArray(ArrayFixtures.longs(arena, 10L, 20L),
                    ArrayFixtures.bools(arena, true, false));

            // When / Then — valid row renders, null row is empty
            assertThat(GridRender.formatCell(sut, 0, I64)).isEqualTo("10");
            assertThat(GridRender.formatCell(sut, 1, I64)).isEmpty();
        }
    }

    @Test
    void unrenderableTypeFallsBackToAngleBrackets() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — StructArray has no scalar rendering
            DType.Struct dtype = new DType.Struct(List.of("a"), List.of(I64), false);
            StructArray sut = new StructArray(dtype, 1, List.of(ArrayFixtures.longs(arena, 1L)));

            // When / Then — falls into the default <ClassName> branch
            assertThat(GridRender.formatCell(sut, 0, dtype)).isEqualTo("<StructArray>");
        }
    }
}
