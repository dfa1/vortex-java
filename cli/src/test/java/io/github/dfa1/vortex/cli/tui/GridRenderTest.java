package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyConstantDecimalArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.extension.TimeExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.TimestampExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.UuidExtensionDecoder;
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
    void rendersTimeExtensionFromIntStorage() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — vortex.time (ms unit) over I32 storage; 0 ms-of-day = midnight
            DType timeExt = TimeExtensionDecoder.INSTANCE.dtype(TimeUnit.Milliseconds, false);
            IntArray storage = ArrayFixtures.ints(arena, 0);

            // When / Then
            assertThat(GridRender.formatCell(storage, 0, timeExt)).isEqualTo("00:00");
        }
    }

    @Test
    void rendersTimestampExtensionFromLongStorage() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — vortex.timestamp (ms unit, no tz) over I64 storage; 0 ms = the epoch
            DType tsExt = TimestampExtensionDecoder.INSTANCE.dtype(TimeUnit.Milliseconds, null, false);
            LongArray storage = ArrayFixtures.longs(arena, 0L);

            // When / Then
            assertThat(GridRender.formatCell(storage, 0, tsExt)).isEqualTo("1970-01-01T00:00:00Z");
        }
    }

    @Test
    void rendersUuidExtensionFromFixedSizeListStorage() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — vortex.uuid over FixedSizeList(U8,16); all-zero bytes = the nil UUID
            DType uuidExt = UuidExtensionDecoder.INSTANCE.dtype(false);
            ByteArray elems = ArrayFixtures.bytes(arena, new byte[16]);
            FixedSizeListArray storage = new FixedSizeListArray(
                    new DType.FixedSizeList(new DType.Primitive(PType.U8, false), 16, false), 1, elems);

            // When / Then
            assertThat(GridRender.formatCell(storage, 0, uuidExt))
                    .isEqualTo("00000000-0000-0000-0000-000000000000");
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
    void rendersUtf8VarBinAsRawString() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — Utf8 VarBin renders unquoted in the grid
            Array sut = ArrayFixtures.utf8(arena, "héllo", "x");

            // When / Then
            assertThat(GridRender.formatCell(sut, 0, new DType.Utf8(false))).isEqualTo("héllo");
            assertThat(GridRender.formatCell(sut, 1, new DType.Utf8(false))).isEqualTo("x");
        }
    }

    @Test
    void rendersBinaryVarBinAsHex() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — non-Utf8 bytes render as 0x-prefixed hex
            Array sut = ArrayFixtures.binary(arena, new byte[]{0x01, (byte) 0xab, 0x02});

            // When / Then
            assertThat(GridRender.formatCell(sut, 0, new DType.Binary(false))).isEqualTo("0x01ab02");
        }
    }

    @Test
    void truncatesBinaryHexBeyond16Bytes() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — 20 bytes: only the first 16 are shown, then an ellipsis
            byte[] twenty = new byte[20];
            Array sut = ArrayFixtures.binary(arena, twenty);

            // When
            String cell = GridRender.formatCell(sut, 0, new DType.Binary(false));

            // Then — "0x" + 16*"00" + "..."
            assertThat(cell).isEqualTo("0x" + "00".repeat(16) + "...");
        }
    }

    @Test
    void extensionDecodeFailureRendersDiagnostic() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — vortex.date declared but storage is F64, which epochInteger rejects
            DType dateExt = new DType.Extension("vortex.date",
                    new DType.Primitive(PType.I32, false), null, false);
            Array badStorage = ArrayFixtures.doubles(arena, 1.5);

            // When — the decode throws and is caught
            String cell = GridRender.formatCell(badStorage, 0, dateExt);

            // Then — angle-bracketed diagnostic rather than a thrown exception
            assertThat(cell).startsWith("<").endsWith(">").contains("Exception");
        }
    }

    @Test
    void unknownExtensionIdFallsThroughToStorageRendering() {
        try (Arena arena = Arena.ofConfined()) {
            // Given — an extension id this reader does not know: render the raw storage value
            DType unknownExt = new DType.Extension("custom.thing",
                    new DType.Primitive(PType.I32, false), null, false);
            IntArray storage = ArrayFixtures.ints(arena, 42);

            // When / Then — no extension formatting, falls to the IntArray branch
            assertThat(GridRender.formatCell(storage, 0, unknownExt)).isEqualTo("42");
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
