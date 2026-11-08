package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompareTest {

    static Stream<Arguments> fractionalOperands() {
        return Stream.of(
                Arguments.of(1.5, DTypes.I64),
                Arguments.of(1.5f, DTypes.I32),
                Arguments.of(new BigDecimal("1.5"), DTypes.U8),
                // a whole double is rejected too: Rust refuses the f64 dtype, not the fraction
                Arguments.of(2.0, DTypes.I64));
    }

    @ParameterizedTest(name = "{0} vs {1}")
    @MethodSource("fractionalOperands")
    void integerColumn_nonIntegerOperand_throwsVortexException(Object operand, DType column) {
        // Given an integer column value and a float/double/decimal operand. The compare used to
        // truncate it to a long, so `x >= 1.5` matched `x = 1` (#701)
        // When / Then
        assertThatThrownBy(() -> Compare.values(1L, operand, column))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("an integer column compares only with an integer value");
    }

    @Test
    void integerColumn_narrowIntegerOperands_compareByValue() {
        // Given operands boxed at every integer width, which callers may pass for any integer column
        // When
        int result = Compare.values((byte) 1, (short) 2, DTypes.I64) + Compare.values(3, 3L, DTypes.I64);

        // Then — 1 < 2 contributes -1, 3 == 3 contributes 0
        assertThat(result).isEqualTo(-1);
    }

    @Test
    void stringsOrderByUtf8BytesNotUtf16() {
        // Given a supplementary character (a surrogate pair in UTF-16, 4 bytes in UTF-8) and a BMP
        // character above the surrogate range. UTF-16 puts the emoji first; Rust, which writes
        // string zone stats in byte order, puts it last — comparing filters in UTF-16 against
        // those stats pruned zones that held the match.
        String emoji = "\uD83D\uDE00";

        // When
        int result = Compare.values(emoji, "\uE000", DTypes.UTF8);

        // Then
        assertThat(result).isPositive();
    }

    @Test
    void signedColumnOrdersByValue() {
        // Given a signed I64 column
        // When comparing a smaller against a larger value
        int result = Compare.values(1L, 2L, DTypes.I64);

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
        int result = Compare.values(highBit, small, DTypes.U64);

        // Then the high-bit value is the larger
        assertThat(result).isPositive();
    }

    @Test
    void signedColumnTreatsHighBitAsNegative() {
        // Given the same raw bits but a *signed* I64 column
        // When compared
        int result = Compare.values(Long.MIN_VALUE, 1L, DTypes.I64);

        // Then the high-bit value is the smaller (negative) — proving the column dtype, not the
        // boxed operand, drives the mode
        assertThat(result).isNegative();
    }

    @Test
    void floatColumnComparesAsDouble() {
        // Given an F64 column
        // When comparing two doubles
        int result = Compare.values(1.5, 1.25, DTypes.F64);

        // Then double order holds
        assertThat(result).isPositive();
    }

    @Test
    void integerColumnKeepsPrecisionPastDoubleMantissa() {
        // Given two i64 values that differ by one but collide when narrowed to double (> 2^53)
        long a = (1L << 53) + 1;
        long b = (1L << 53) + 2;

        // When compared as an integer column
        int result = Compare.values(a, b, DTypes.I64);

        // Then the integer path keeps the distinction a double-compare would lose
        assertThat(result).isNegative();
    }

    @Test
    void stringColumnUsesNaturalOrder() {
        // Given a Utf8 column
        // When comparing two strings
        int result = Compare.values("apple", "banana", DTypes.UTF8);

        // Then lexicographic order holds
        assertThat(result).isNegative();
    }

    @Test
    void incomparableValuesThrow() {
        // Given a string value against a numeric operand with no resolved primitive column
        // When compared
        // Then the mismatch surfaces rather than silently passing
        assertThatThrownBy(() -> Compare.values("x", 1L, DTypes.UTF8))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("not comparable");
    }

    static Stream<Arguments> decimalPairs() {
        DType decimal = new DType.Decimal((byte) 10, (byte) 2, false);
        DType wide = new DType.Decimal((byte) 38, (byte) 0, false);
        BigDecimal i128 = new BigDecimal("170141183460469231731687303715884105727");
        return Stream.of(
                // 1.5 and 1.2 are both 1 as a long
                Arguments.of("by value, not the truncated long", decimal, new BigDecimal("1.5"), new BigDecimal("1.2"), 1),
                Arguments.of("same value, two scales", decimal, new BigDecimal("1.50"), new BigDecimal("1.5"), 0),
                Arguments.of("negative", decimal, new BigDecimal("-0.01"), new BigDecimal("0.00"), -1),
                Arguments.of("negative scale", decimal, new BigDecimal("1E+2"), 100L, 0),
                Arguments.of("long operand", decimal, new BigDecimal("2.50"), 2L, 1),
                Arguments.of("long on the left", decimal, 1L, new BigDecimal("1.00"), 0),
                Arguments.of("double operand", decimal, new BigDecimal("2.50"), 2.75d, -1),
                Arguments.of("float operand", decimal, new BigDecimal("2.50"), 2.25f, 1),
                Arguments.of("past a long", wide, i128, Long.MAX_VALUE, 1),
                Arguments.of("big integer operand", decimal, new BigDecimal("1"), BigInteger.TWO.pow(64), -1),
                Arguments.of("no column", null, new BigDecimal("1.5"), new BigDecimal("1.2"), 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decimalPairs")
    void decimalsCompareByValue(String name, DType column, Object a, Object b, int sign) {
        // Given a decimal column (or none) and operands of mixed types

        // When
        int result = Compare.values(a, b, column);

        // Then
        assertThat(Integer.signum(result)).isEqualTo(sign);
    }

    static Stream<Arguments> notDecimalValues() {
        DType decimal = new DType.Decimal((byte) 10, (byte) 2, false);
        return Stream.of(
                Arguments.of("NaN", Double.NaN),
                Arguments.of("infinity", Double.POSITIVE_INFINITY),
                Arguments.of("float NaN", Float.NaN),
                Arguments.of("text", "x"),
                Arguments.of("null", null)).map(a -> Arguments.of(a.get()[0], decimal, a.get()[1]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notDecimalValues")
    void decimalAgainstAValueWithNoDecimalMeaningThrows(String name, DType column, Object operand) {
        // Given / When / Then
        assertThatThrownBy(() -> Compare.values(new BigDecimal("1"), operand, column))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("not comparable to a decimal");
    }

    @Test
    void primitiveColumnsKeepTheirOwnOrderWhateverTheOperand() {
        // Given a U64 value past 2^63 (a negative long), and a float column holding NaN. A decimal
        // operand on the integer column is rejected (#701), so that side uses an integer one
        long big = -1L;

        // When
        int unsigned = Compare.values(big, 2L, DTypes.U64);
        int nan = Compare.values(Double.NaN, new BigDecimal("1.5"), DTypes.F64);

        // Then the unsigned order and the double order hold, no decimal conversion
        assertThat(unsigned).isPositive();
        assertThat(nan).isPositive();
    }
}
