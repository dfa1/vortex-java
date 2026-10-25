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
            "1073741823,         1024.0 MB",  // last value below the GB boundary
            "1073741824,         1.0 GB",     // first GB
            "2284919630,         2.1 GB",     // a 100M-row file read as 2179.1 MB before GB existed
            "1099511627775,      1024.0 GB",  // last value below the TB boundary
            "1099511627776,      1.0 TB",
            "1125899906842624,   1.0 PB",
            "1152921504606846975, 1024.0 PB", // last value below the EB boundary
            "1152921504606846976, 1.0 EB",
            "9223372036854775807, 8.0 EB",    // Long.MAX_VALUE: the largest count there is, still in a unit
    })
    void format_rendersBinaryUnits(long bytes, String expected) {
        // Given a byte count

        // When
        String result = ByteSize.format(bytes);

        // Then it picks B, or KB up to EB with one decimal
        assertThat(result).isEqualTo(expected);
    }
}
