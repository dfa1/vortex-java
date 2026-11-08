package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.compute.Predicate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ScanIteratorDecimalStatsTest {

    private static final DType DECIMAL = new DType.Decimal((byte) 10, (byte) 2, false);

    @Test
    void canPrune_decimalBoundsAsText_doesNotPruneOrThrow() {
        // Given a flat node's stats, whose decimal scalar was read without the column's dtype
        ArrayStats stats = stats("\u0001\u0002", "\u0003\u0004");

        // When / Then
        assertThat(ScanIterator.canPrune(new Predicate.Gte(new BigDecimal("1.00")), stats, 10, DECIMAL)).isFalse();
        assertThat(ScanIterator.canPrune(new Predicate.Eq(new BigDecimal("1.00")), stats, 10, DECIMAL)).isFalse();
    }

    @Test
    void canPrune_decimalBounds_prunesOutsideTheRange() {
        // Given
        ArrayStats stats = stats(new BigDecimal("1.00"), new BigDecimal("5.00"));

        // When / Then
        assertThat(ScanIterator.canPrune(new Predicate.Gte(new BigDecimal("5.01")), stats, 10, DECIMAL)).isTrue();
        assertThat(ScanIterator.canPrune(new Predicate.Gte(new BigDecimal("5.00")), stats, 10, DECIMAL)).isFalse();
    }

    private static ArrayStats stats(Object min, Object max) {
        return new ArrayStats(min, true, max, true, null, null, null, null, 0L, null, null);
    }
}
