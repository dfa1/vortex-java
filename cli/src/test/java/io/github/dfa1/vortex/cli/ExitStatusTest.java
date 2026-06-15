package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExitStatusTest {

    @Test
    void codesArePosixCompliant() {
        // Given / When / Then — OK must be 0 (only value POSIX shells treat as success);
        // other codes must be distinct positive values so callers can branch on them.
        assertThat(ExitStatus.OK).isZero();
        assertThat(ExitStatus.USAGE_ERROR).isPositive();
        assertThat(ExitStatus.FILE_NOT_FOUND).isPositive();
        assertThat(ExitStatus.ERROR).isPositive();
        assertThat(ExitStatus.USAGE_ERROR)
                .isNotEqualTo(ExitStatus.FILE_NOT_FOUND)
                .isNotEqualTo(ExitStatus.ERROR);
        assertThat(ExitStatus.FILE_NOT_FOUND).isNotEqualTo(ExitStatus.ERROR);
    }
}
