package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for [InspectorRender]: pure formatters exercised directly against
/// in-memory arrays — no terminal, worker, or encoded fixture required.
class InspectorRenderTest {

    private static final DType I64 = new DType.Primitive(PType.I64, false);

    @Nested
    class FormatValue {

        @Test
        void rendersEachNumericAndBoolType() {
            try (Arena arena = Arena.ofConfined()) {
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
    }

    @Nested
    class FormatHexRow {

        @Test
        void rendersFullRowWithAsciiGutter() {
            // 16 printable bytes 'A'..'P' -> hex columns + ASCII gutter
            byte[] data = new byte[16];
            for (int i = 0; i < 16; i++) {
                data[i] = (byte) ('A' + i);
            }

            String row = InspectorRender.formatHexRow(data, 0);

            assertThat(row).startsWith("00000000  ");
            assertThat(row).contains("41 42 43"); // A B C
            assertThat(row).contains("|ABCDEFGHIJKLMNOP|");
        }

        @Test
        void padsShortTrailingRowAndDotsNonPrintable() {
            // 3 bytes incl a non-printable 0x00 -> '.' in the gutter, spaces for missing cols
            byte[] data = {(byte) 'x', 0x00, (byte) 'y'};

            String row = InspectorRender.formatHexRow(data, 0);

            assertThat(row).startsWith("00000000  ");
            assertThat(row).contains("78 00 79");
            assertThat(row).contains("|x.y");
        }
    }

    @Nested
    class FormatBytes {

        @Test
        void formatsAcrossUnitBoundaries() {
            assertThat(InspectorRender.formatBytes(512)).isEqualTo("512 B");
            assertThat(InspectorRender.formatBytes(2048)).isEqualTo("2.0 KB");
            assertThat(InspectorRender.formatBytes(3 * 1024 * 1024)).isEqualTo("3.0 MB");
        }
    }

    @Nested
    class PadAndTruncate {

        @Test
        void padExtendsToWidth() {
            assertThat(InspectorRender.pad("ab", 5)).isEqualTo("ab   ").hasSize(5);
        }

        @Test
        void padTruncatesWhenTooLong() {
            assertThat(InspectorRender.pad("abcdef", 3)).isEqualTo("abc");
        }

        @Test
        void truncateLeavesShortStringsUntouched() {
            assertThat(InspectorRender.truncate("ab", 5)).isEqualTo("ab");
            assertThat(InspectorRender.truncate("abcdef", 3)).isEqualTo("abc");
        }
    }
}
