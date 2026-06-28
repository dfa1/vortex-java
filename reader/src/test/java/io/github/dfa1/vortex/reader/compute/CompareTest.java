package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompareTest {

    @Test
    void signedColumnOrdersByValue() {
        // Given a signed I64 column
        // When comparing a smaller against a larger value
        int result = Compare.values(1L, 2L, DType.I64);

        // Then the natural signed order holds
        assertThat(result).isNegative();
    }

    @Test
    void unsignedColumnTreatsHighBitAsLarge() {
        // Given a U64 column where the raw bits of the larger value set the sign bit, so a signed
        // compare would wrongly call it negative (this is the bug the unsigned path guards against)
        long highBit = Long.MIN_VALUE; // 2^63 as raw bits
        long small = 1L;

        // When comparing the high-bit value against the small one under unsigned semantics
        int result = Compare.values(highBit, small, DType.U64);

        // Then the high-bit value is the larger
        assertThat(result).isPositive();
    }

    @Test
    void signedColumnTreatsHighBitAsNegative() {
        // Given the same raw bits but a *signed* I64 column
        // When compared
        int result = Compare.values(Long.MIN_VALUE, 1L, DType.I64);

        // Then the high-bit value is the smaller (negative) — proving the column dtype, not the
        // boxed operand, drives the mode
        assertThat(result).isNegative();
    }

    @Test
    void floatColumnComparesAsDouble() {
        // Given an F64 column
        // When comparing two doubles
        int result = Compare.values(1.5, 1.25, DType.F64);

        // Then double order holds
        assertThat(result).isPositive();
    }

    @Test
    void integerColumnKeepsPrecisionPastDoubleMantissa() {
        // Given two i64 values that differ by one but collide when narrowed to double (> 2^53)
        long a = (1L << 53) + 1;
        long b = (1L << 53) + 2;

        // When compared as an integer column
        int result = Compare.values(a, b, DType.I64);

        // Then the integer path keeps the distinction a double-compare would lose
        assertThat(result).isNegative();
    }

    @Test
    void stringColumnUsesNaturalOrder() {
        // Given a Utf8 column
        // When comparing two strings
        int result = Compare.values("apple", "banana", DType.UTF8);

        // Then lexicographic order holds
        assertThat(result).isNegative();
    }

    @Test
    void incomparableValuesThrow() {
        // Given a string value against a numeric operand with no resolved primitive column
        // When compared
        // Then the mismatch surfaces rather than silently passing
        assertThatThrownBy(() -> Compare.values("x", 1L, DType.UTF8))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("not comparable");
    }
}
