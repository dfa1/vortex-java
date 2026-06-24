package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [TimeUnit#fromTag(byte)] and [TimeUnit#divisor()].
class TimeUnitTest {

    @ParameterizedTest
    @EnumSource(TimeUnit.class)
    void fromTag_mapsEachOrdinalBack(TimeUnit unit) {
        // Given — the wire tag is the unsigned ordinal
        byte tag = (byte) unit.ordinal();

        // When
        TimeUnit result = TimeUnit.fromTag(tag);

        // Then
        assertThat(result).isEqualTo(unit);
    }

    @Test
    void fromTag_tagEqualToValueCount_throws() {
        // Given — the first tag past the last valid ordinal (Days == 4, so 5 is out of range)
        byte tag = (byte) TimeUnit.values().length;

        // When / Then
        assertThatThrownBy(() -> TimeUnit.fromTag(tag))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown TimeUnit tag: 5");
    }

    @ParameterizedTest
    @ValueSource(ints = {0x80, 0xFF})
    void fromTag_highBitByte_isReadUnsigned_andThrows(int unsignedValue) {
        // Given — a byte whose sign bit is set; signed it would be negative, but fromTag must
        // read it unsigned (e.g. 0xFF -> 255), so it lands out of range rather than indexing
        // values()[-1] with an ArrayIndexOutOfBoundsException.
        byte tag = (byte) unsignedValue;

        // When / Then
        assertThatThrownBy(() -> TimeUnit.fromTag(tag))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown TimeUnit tag: " + unsignedValue);
    }

    @ParameterizedTest
    @CsvSource({
            "Nanoseconds,  1000000000",
            "Microseconds, 1000000",
            "Milliseconds, 1000",
            "Seconds,      1"
    })
    void divisor_returnsDivisionsPerSecond(TimeUnit unit, long expected) {
        // When
        long result = unit.divisor();

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void divisor_days_throws() {
        // Given — Days is not sub-dividable into a per-second count
        TimeUnit sut = TimeUnit.Days;

        // When / Then
        assertThatThrownBy(sut::divisor)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Days cannot be split");
    }
}
