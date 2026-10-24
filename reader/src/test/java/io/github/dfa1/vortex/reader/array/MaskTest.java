package io.github.dfa1.vortex.reader.array;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A filtered scan copies each column through these forms, so a wrong index or run here returns
/// the wrong rows. Rows 0, 63, 64 and the last row sit on 64-bit word edges, where a shift or a
/// word index off by one shows.
class MaskTest {

    @Nested
    class Construction {

        @Test
        void aBitmapKeepingNoRow_isAllFalse() {
            // Given
            long[] words = new long[2];

            // When
            Mask result = Mask.fromBitmap(words, 100);

            // Then
            assertThat(result).isEqualTo(new Mask.AllFalse(100));
        }

        @Test
        void aBitmapKeepingEveryRow_isAllTrue() {
            // Given: 70 rows, the last word partly filled
            long[] words = {-1L, (1L << 6) - 1};

            // When
            Mask result = Mask.fromBitmap(words, 70);

            // Then
            assertThat(result).isEqualTo(new Mask.AllTrue(70));
        }

        @Test
        void bitsPastTheLastRow_areNotKeptRows() {
            // Given: rows 0 and 1 of 3 kept, plus stray bits past row 2 that are no rows at all;
            // counted, they would make the mask keep more rows than it has
            long[] words = {0b11L | -1L << 3};

            // When
            Mask result = Mask.fromBitmap(words, 3);

            // Then
            assertThat(result.trueCount()).isEqualTo(2);
            assertThat(((Mask.Values) result).indices()).containsExactly(0, 1);
        }

        @Test
        void aBitmapWithTheWrongWordCount_isRejected() {
            // Given: 65 rows need two words
            long[] words = new long[1];

            // When / Then
            assertThatThrownBy(() -> Mask.fromBitmap(words, 65))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void indicesKeepingSomeRows_keepTheirOrderAndCount() {
            // Given
            int[] rows = {0, 63, 64, 129};

            // When
            Mask result = Mask.Values.fromIndices(130, rows);

            // Then
            assertThat(result).isInstanceOf(Mask.Values.class);
            Mask.Values values = (Mask.Values) result;
            assertThat(values.trueCount()).isEqualTo(4);
            assertThat(values.indices()).containsExactly(0, 63, 64, 129);
            assertThat(values.density()).isEqualTo(4 / 130.0);
        }

        @Test
        void noIndices_isAllFalseAndEveryIndex_isAllTrue() {
            // Given
            int[] none = {};
            int[] all = {0, 1, 2};

            // When
            Mask noneResult = Mask.Values.fromIndices(3, none);
            Mask allResult = Mask.Values.fromIndices(3, all);

            // Then
            assertThat(noneResult).isEqualTo(new Mask.AllFalse(3));
            assertThat(allResult).isEqualTo(new Mask.AllTrue(3));
        }
    }

    @Nested
    class DerivedForms {

        @Test
        void indices_walkTheBitmapAcrossWords() {
            // Given: the same rows as a bitmap, so the indices come from the word walk
            long[] words = new long[3];
            for (int row : new int[]{0, 63, 64, 129}) {
                words[row >>> 6] |= 1L << row;
            }
            Mask.Values sut = (Mask.Values) Mask.fromBitmap(words, 130);

            // When
            int[] result = sut.indices();

            // Then
            assertThat(result).containsExactly(0, 63, 64, 129);
            assertThat(sut.indices()).isSameAs(result);
        }

        @Test
        void slices_coverRunsAcrossAWordEdgeAndEndingAtTheLastRow() {
            // Given: rows 2, 60..69 (crossing the word edge at 64) and 125..129 (the last row)
            int[] rows = IntStream.concat(IntStream.of(2),
                    IntStream.concat(IntStream.range(60, 70), IntStream.range(125, 130))).toArray();
            Mask.Values sut = (Mask.Values) Mask.Values.fromIndices(130, rows);

            // When
            int[] result = sut.slices();

            // Then
            assertThat(result).containsExactly(2, 3, 60, 70, 125, 130);
            assertThat(sut.slices()).isSameAs(result);
        }
    }

    @Nested
    class Limit {

        @Test
        void aLimitAtOrAboveTheKeptRows_keepsTheMask() {
            // Given
            Mask sut = Mask.Values.fromIndices(10, new int[]{1, 5});

            // When
            Mask result = sut.limit(2);

            // Then
            assertThat(result).isSameAs(sut);
        }

        @Test
        void allTrue_keepsTheFirstRowsAcrossAWordEdge() {
            // Given
            Mask sut = new Mask.AllTrue(130);

            // When
            Mask result = sut.limit(70);

            // Then
            assertThat(result.trueCount()).isEqualTo(70);
            assertThat(((Mask.Values) result).indices()).isEqualTo(IntStream.range(0, 70).toArray());
        }

        @Test
        void values_keepTheFirstKeptRowsCuttingInsideAWord() {
            // Given: the cut falls inside the second word, between rows 64 and 100
            Mask sut = Mask.Values.fromIndices(130, new int[]{3, 63, 64, 100, 129});

            // When
            Mask result = sut.limit(3);

            // Then
            assertThat(result.length()).isEqualTo(130);
            assertThat(((Mask.Values) result).indices()).containsExactly(3, 63, 64);
        }

        @Test
        void aZeroLimit_keepsNoRow() {
            // Given
            Mask sut = Mask.Values.fromIndices(10, new int[]{1, 5});

            // When
            Mask result = sut.limit(0);

            // Then
            assertThat(result).isEqualTo(new Mask.AllFalse(10));
        }
    }

    /// Random lengths around the word edges and random densities, so runs start and end on and
    /// next to every word boundary; each form is checked against a plain list of the kept rows.
    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void everyForm_agreesWithTheKeptRows(long seed) {
        // Given
        Random random = new Random(seed);
        int length = 1 + random.nextInt(300);
        double density = random.nextDouble();
        long[] words = new long[(length + 63) >>> 6];
        List<Integer> kept = new ArrayList<>();
        for (int row = 0; row < length; row++) {
            if (random.nextDouble() < density) {
                words[row >>> 6] |= 1L << row;
                kept.add(row);
            }
        }
        int limit = random.nextInt(length + 1);

        // When
        Mask result = Mask.fromBitmap(words, length);
        Mask limited = result.limit(limit);

        // Then
        assertThat(result.trueCount()).isEqualTo(kept.size());
        assertThat(rowsOf(result)).isEqualTo(kept);
        assertThat(rowsOf(limited)).isEqualTo(kept.subList(0, Math.min(limit, kept.size())));
        if (result instanceof Mask.Values values) {
            List<Integer> fromSlices = new ArrayList<>();
            int[] slices = values.slices();
            for (int s = 0; s < slices.length; s += 2) {
                assertThat(slices[s]).as("empty run").isLessThan(slices[s + 1]);
                if (s > 0) {
                    assertThat(slices[s]).as("touching runs").isGreaterThan(slices[s - 1]);
                }
                for (int row = slices[s]; row < slices[s + 1]; row++) {
                    fromSlices.add(row);
                }
            }
            assertThat(fromSlices).isEqualTo(kept);
        }
    }

    static LongStream seeds() {
        return LongStream.range(0, 30);
    }

    private static List<Integer> rowsOf(Mask mask) {
        return switch (mask) {
            case Mask.AllTrue(int length) -> IntStream.range(0, length).boxed().toList();
            case Mask.AllFalse _ -> List.of();
            case Mask.Values values -> IntStream.of(values.indices()).boxed().toList();
        };
    }
}
