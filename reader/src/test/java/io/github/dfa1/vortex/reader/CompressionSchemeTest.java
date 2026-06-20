package io.github.dfa1.vortex.reader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompressionSchemeTest {

    @ParameterizedTest(name = "code {0} -> {1}")
    @CsvSource({"0,NONE", "1,LZ4", "2,ZLIB", "3,ZSTD"})
    void ofMapsEveryCode(int code, CompressionScheme expected) {
        // When / Then
        assertThat(CompressionScheme.of(code)).isEqualTo(expected);
        assertThat(expected.code).isEqualTo(code);
    }

    @Test
    void unknownCodeRejected() {
        // When / Then
        assertThatThrownBy(() -> CompressionScheme.of(99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown compression code");
    }
}
