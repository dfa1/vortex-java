package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.GenericArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyConstantDecimalArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Unit tests for [InspectorRender]: pure formatters exercised directly against
/// in-memory arrays — no terminal, worker, or encoded fixture required.
class InspectorRenderTest {

    private static final DType I64 = new DType.Primitive(PType.I64, false);

    @Nested
    class FormatValue {

        @Test
        void rendersEachNumericAndBoolType() {
            try (Arena arena = Arena.ofConfined()) {
                // Given / When / Then — each primitive family formats via its accessor
                assertThat(InspectorRender.formatValue(ArrayFixtures.longs(arena, 42L), 0, I64))
                        .isEqualTo("42");
                assertThat(InspectorRender.formatValue(ArrayFixtures.ints(arena, -7), 0, I64))
                        .isEqualTo("-7");
                assertThat(InspectorRender.formatValue(ArrayFixtures.shorts(arena, (short) 5), 0, I64))
                        .isEqualTo("5");
                assertThat(InspectorRender.formatValue(ArrayFixtures.bytes(arena, (byte) 9), 0, I64))
                        .isEqualTo("9");
                assertThat(InspectorRender.formatValue(ArrayFixtures.doubles(arena, 1.5), 0, I64))
                        .isEqualTo("1.5");
                assertThat(InspectorRender.formatValue(ArrayFixtures.floats(arena, 2.5f), 0, I64))
                        .isEqualTo("2.5");
                assertThat(InspectorRender.formatValue(ArrayFixtures.bools(arena, true, false), 1, I64))
                        .isEqualTo("false");
            }
        }

        @Test
        void rendersDecimal() {
            // Given
            DType decimal = new DType.Decimal((byte) 10, (byte) 2, false);
            Array sut = new LazyConstantDecimalArray(decimal, 2, new BigDecimal("3.14"), 8);

            // When / Then
            assertThat(InspectorRender.formatValue(sut, 0, decimal)).isEqualTo("3.14");
        }

        @Test
        void rendersGenericArrayDecimal() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — GenericArray over an 8-byte LE mantissa 123 at scale 2 → 1.23
                DType decimal = new DType.Decimal((byte) 10, (byte) 2, false);
                MemorySegment buf = arena.allocate(8, 8);
                buf.setAtIndex(ValueLayout.JAVA_LONG, 0, 123L);
                Array sut = new GenericArray(decimal, 1, buf.asReadOnly());

                // When / Then
                assertThat(InspectorRender.formatValue(sut, 0, decimal)).isEqualTo("1.23");
            }
        }

        @Test
        void genericArrayDecimalBadShapeRendersFallback() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — two buffers: getDecimal rejects the shape with a non-"null cell" error
                DType decimal = new DType.Decimal((byte) 10, (byte) 2, false);
                MemorySegment buf = arena.allocate(8, 8);
                Array sut = new GenericArray(decimal, 1, new MemorySegment[]{buf, buf}, new Array[0]);

                // When / Then — tryDecimal swallows it into the <ClassName dtype> fallback
                assertThat(InspectorRender.formatValue(sut, 0, decimal)).startsWith("<GenericArray");
            }
        }

        @Test
        void rendersDateExtension() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — vortex.date over I32 epoch-day storage
                DType dateExt = new DType.Extension("vortex.date",
                        new DType.Primitive(PType.I32, false), null, false);
                IntArray storage = ArrayFixtures.ints(arena, 0);

                // When / Then — day 0 = 1970-01-01
                assertThat(InspectorRender.formatValue(storage, 0, dateExt)).isEqualTo("1970-01-01");
            }
        }

        @Test
        void rendersUtf8VarBinQuoted() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — the inspector quotes Utf8 strings to make whitespace visible
                Array sut = ArrayFixtures.utf8(arena, "hi");

                // When / Then
                assertThat(InspectorRender.formatValue(sut, 0, new DType.Utf8(false))).isEqualTo("\"hi\"");
            }
        }

        @Test
        void rendersBinaryVarBinAsShortHex() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — non-Utf8 bytes render as 0x-prefixed hex
                Array sut = ArrayFixtures.binary(arena, new byte[]{0x01, (byte) 0xab});

                // When / Then
                assertThat(InspectorRender.formatValue(sut, 0, new DType.Binary(false))).isEqualTo("0x01ab");
            }
        }

        @Test
        void dateExtensionDecodeFailureFallsThroughToGeneric() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — vortex.date declared but storage is F64; the decode throws and is swallowed
                DType dateExt = new DType.Extension("vortex.date",
                        new DType.Primitive(PType.I32, false), null, false);
                Array badStorage = ArrayFixtures.doubles(arena, 1.5);

                // When / Then — falls through to the generic DoubleArray rendering
                assertThat(InspectorRender.formatValue(badStorage, 0, dateExt)).isEqualTo("1.5");
            }
        }

        @Test
        void unknownTypeFallsBackToAngleBrackets() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — StructArray has no scalar rendering
                DType.Struct dtype = new DType.Struct(List.of("a"), List.of(I64), false);
                StructArray sut = new StructArray(dtype, 1, List.of(ArrayFixtures.longs(arena, 1L)));

                // When / Then — falls into the default <ClassName dtype> branch
                assertThat(InspectorRender.formatValue(sut, 0, dtype)).contains("StructArray");
            }
        }
    }

    @Nested
    class FormatStatsArray {

        @Test
        void rendersOneRowPerStructEntry() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                DType.Struct statsDtype = new DType.Struct(List.of("min"), List.of(I64), false);
                StructArray stats = new StructArray(statsDtype, 2, List.of(ArrayFixtures.longs(arena, 5L, 6L)));

                // When
                List<String> rows = InspectorRender.formatStatsArray(stats, statsDtype);

                // Then
                assertThat(rows).containsExactly("min=5", "min=6");
            }
        }

        @Test
        void maskedInvalidStatsCellRendersNull() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — field masked: row 0 valid (5), row 1 null
                DType.Struct statsDtype = new DType.Struct(List.of("min"), List.of(I64), false);
                MaskedArray field = new MaskedArray(ArrayFixtures.longs(arena, 5L, 9L),
                        ArrayFixtures.bools(arena, true, false));
                StructArray stats = new StructArray(statsDtype, 2, List.of(field));

                // When
                List<String> rows = InspectorRender.formatStatsArray(stats, statsDtype);

                // Then
                assertThat(rows).containsExactly("min=5", "min=null");
            }
        }

        @Test
        void multiFieldNonStructStatsArrayThrows() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — a multi-field stats schema but a non-struct payload
                DType.Struct statsDtype = new DType.Struct(List.of("min", "max"), List.of(I64, I64), false);
                Array notAStruct = ArrayFixtures.longs(arena, 1L);

                // When / Then
                assertThatThrownBy(() -> InspectorRender.formatStatsArray(notAStruct, statsDtype))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("not a struct");
            }
        }

        @Test
        void singleFieldStatsArrayRendersBareField() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — a single-field (NULL_COUNT-only) stats table decodes to the bare field,
                // not a StructArray; the renderer must still render it
                DType.Struct statsDtype = new DType.Struct(
                        List.of("null_count"), List.of(new DType.Primitive(PType.U64, true)), false);
                Array oneStat = ArrayFixtures.longs(arena, 0L, 2L);

                // When
                List<String> rows = InspectorRender.formatStatsArray(oneStat, statsDtype);

                // Then one row per zone, each showing the stat
                assertThat(rows).containsExactly("null_count=0", "null_count=2");
            }
        }
    }

    @Nested
    class FormatHexRow {

        @Test
        void rendersFullRowWithAsciiGutter() {
            // Given — 16 printable bytes 'A'..'P'
            byte[] data = new byte[16];
            for (int i = 0; i < 16; i++) {
                data[i] = (byte) ('A' + i);
            }

            // When
            String row = InspectorRender.formatHexRow(data, 0);

            // Then — offset, hex columns, ASCII gutter
            assertThat(row).startsWith("00000000  ");
            assertThat(row).contains("41 42 43"); // A B C
            assertThat(row).contains("|ABCDEFGHIJKLMNOP|");
        }

        @Test
        void padsShortTrailingRowAndDotsNonPrintable() {
            // Given — 3 bytes including a non-printable 0x00
            byte[] data = {(byte) 'x', 0x00, (byte) 'y'};

            // When
            String row = InspectorRender.formatHexRow(data, 0);

            // Then — non-printable shows as '.', missing columns are padded
            assertThat(row).startsWith("00000000  ");
            assertThat(row).contains("78 00 79");
            assertThat(row).contains("|x.y");
        }
    }

    @Nested
    class PadAndTruncate {

        @Test
        void padExtendsToWidth() {
            // Given / When / Then — pads with trailing spaces to exact width
            assertThat(InspectorRender.pad("ab", 5)).isEqualTo("ab   ").hasSize(5);
        }

        @Test
        void padTruncatesWhenTooLong() {
            // Given / When / Then — over-width input is cut to width
            assertThat(InspectorRender.pad("abcdef", 3)).isEqualTo("abc");
        }

        @Test
        void truncateLeavesShortStringsUntouched() {
            // Given / When / Then — only over-width strings are cut
            assertThat(InspectorRender.truncate("ab", 5)).isEqualTo("ab");
            assertThat(InspectorRender.truncate("abcdef", 3)).isEqualTo("abc");
        }
    }
}
