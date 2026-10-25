package io.github.dfa1.vortex.inspect;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnDistributionTest {

    @Test
    void bars_fewNumbersListEachValueInValueOrder() {
        // Given — a rating column: 5 bars in order beat a 30-bin histogram with 25 empty bins
        Map<Object, Long> counts = Map.of(3L, 10L, 1L, 2L, 5L, 7L);

        // When
        Map.Entry<List<ColumnDistribution.Bar>, Boolean> result =
                ColumnDistribution.bars(counts, new long[ColumnDistribution.BINS], 1, 5);

        // Then
        assertThat(result.getValue()).isFalse();
        assertThat(result.getKey()).extracting(ColumnDistribution.Bar::value).containsExactly(1L, 3L, 5L);
    }

    @Test
    void bars_fewNamesListMostFrequentFirst() {
        // Given
        Map<Object, Long> counts = Map.of("CASH", 3L, "STOCK", 9L, "BOND", 1L);

        // When
        Map.Entry<List<ColumnDistribution.Bar>, Boolean> result = ColumnDistribution.bars(counts, null, 0, 0);

        // Then
        assertThat(result.getKey()).extracting(ColumnDistribution.Bar::value).containsExactly("STOCK", "CASH", "BOND");
    }

    @Test
    void bars_manyNumbersBecomeAHistogramSpanningMinToMax() {
        // Given — more distinct values than are worth listing
        Map<Object, Long> counts = new HashMap<>();
        for (long v = 0; v <= ColumnDistribution.MAX_CATEGORIES; v++) {
            counts.put(v, 1L);
        }
        long[] bins = new long[ColumnDistribution.BINS];
        bins[0] = 4;

        // When
        Map.Entry<List<ColumnDistribution.Bar>, Boolean> result = ColumnDistribution.bars(counts, bins, 0, 30);

        // Then — the last bin ends exactly at max, not at a rounded lo + 30 * width
        assertThat(result.getValue()).isTrue();
        assertThat(result.getKey()).hasSize(ColumnDistribution.BINS);
        assertThat(result.getKey().getFirst()).isEqualTo(new ColumnDistribution.Bar(0.0, 1.0, 4));
        assertThat(result.getKey().getLast().upper()).isEqualTo(30.0);
    }

    @Test
    void bars_manyNamesShowTheMostFrequent() {
        // Given
        Map<Object, Long> counts = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            counts.put("v" + i, (long) i);
        }

        // When
        Map.Entry<List<ColumnDistribution.Bar>, Boolean> result = ColumnDistribution.bars(counts, null, 0, 0);

        // Then
        assertThat(result.getKey()).hasSize(ColumnDistribution.TOP_VALUES);
        assertThat(result.getKey().getFirst().value()).isEqualTo("v99");
    }

    @Test
    void bars_tooManyToCountAndNoHistogramShowsNothing() {
        // Given — a high-cardinality string column: no honest picture to draw

        // When
        Map.Entry<List<ColumnDistribution.Bar>, Boolean> result = ColumnDistribution.bars(null, null, 0, 0);

        // Then
        assertThat(result.getKey()).isEmpty();
    }

    @Test
    void mean_dividesByPresentValuesOnly() {
        // Given — 10 rows, 2 null
        ColumnDistribution sut = new ColumnDistribution(10, 2, 40L, null, List.of(), false, null);

        // When
        Double result = sut.mean();

        // Then
        assertThat(result).isEqualTo(5.0);
    }
}
