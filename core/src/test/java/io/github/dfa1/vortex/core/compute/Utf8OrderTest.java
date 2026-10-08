package io.github.dfa1.vortex.core.compute;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class Utf8OrderTest {

    @ParameterizedTest
    @CsvSource({
            // the case String#compareTo gets backwards: a surrogate pair against a char >= U+E000
            "😀, ",
            ", 😀",
            // two supplementary characters differing only in the low surrogate
            "😀, 😁",
            // a prefix sorts first
            "ab, abc",
            "abc, ab",
            "abc, abc",
            "a, é",
    })
    void agreesWithUnsignedUtf8ByteOrder(String a, String b) {
        // Given the order Rust compares strings in: their UTF-8 bytes, unsigned
        int expected = Integer.signum(Arrays.compareUnsigned(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));

        // When
        int result = Utf8Order.compare(a, b);

        // Then
        assertThat(Integer.signum(result)).isEqualTo(expected);
    }
}
