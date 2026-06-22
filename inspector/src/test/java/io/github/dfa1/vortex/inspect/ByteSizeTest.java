package io.github.dfa1.vortex.inspect;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ByteSizeTest {

    @ParameterizedTest
    @CsvSource({
            // bytes,            expected
            "0,                  0 B",
            "1,                  1 B",
            "1023,               1023 B",     // last value below the KB boundary
            "1024,               1.0 KB",     // first KB
            "1536,               1.5 KB",     // one-decimal rounding
            "1048575,            1024.0 KB",  // last value below the MB boundary
            "1048576,            1.0 MB",     // first MB
            "1572864,            1.5 MB",
            "5242880,            5.0 MB",
    })
    void format_rendersBinaryUnits(long bytes, String expected) {
        // Given a byte count

        // When
        String result = ByteSize.format(bytes);

        // Then it picks B / KB / MB with one decimal for the scaled forms
        assertThat(result).isEqualTo(expected);
    }
}
