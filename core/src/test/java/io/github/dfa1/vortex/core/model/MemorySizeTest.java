package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemorySizeTest {

    @Test
    void constructor_positiveBytes_succeeds() {
        // Given / When
        MemorySize result = new MemorySize(42);

        // Then
        assertThat(result.bytes()).isEqualTo(42);
    }

    @Test
    void constructor_zeroBytes_succeeds() {
        // Given / When
        MemorySize result = new MemorySize(0);

        // Then
        assertThat(result.bytes()).isZero();
    }

    @Test
    void constructor_negativeBytes_throwsIllegalArgumentException() {
        // Given / When / Then
        assertThatThrownBy(() -> new MemorySize(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofKiB_convertsToBytes() {
        // Given / When
        MemorySize result = MemorySize.ofKiB(4);

        // Then
        assertThat(result.bytes()).isEqualTo(4L * 1024);
    }

    @Test
    void ofMiB_convertsToBytes() {
        // Given / When
        MemorySize result = MemorySize.ofMiB(4);

        // Then
        assertThat(result.bytes()).isEqualTo(4L * 1024 * 1024);
    }

    @Test
    void ofGiB_convertsToBytes() {
        // Given / When
        MemorySize result = MemorySize.ofGiB(2);

        // Then
        assertThat(result.bytes()).isEqualTo(2L * 1024 * 1024 * 1024);
    }

    @Test
    void ofKiB_negativeCount_throwsIllegalArgumentException() {
        // Given — the compact constructor's guard also catches negative factory inputs
        // When / Then
        assertThatThrownBy(() -> MemorySize.ofKiB(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equals_sameBytes_areEqual() {
        // Given / When / Then
        assertThat(MemorySize.ofMiB(1)).isEqualTo(new MemorySize(1024 * 1024));
    }

    @Test
    void toGiB_convertsToFractionalGibibytes() {
        // Given — 1.5 GiB expressed in bytes
        MemorySize sut = new MemorySize(1024L * 1024 * 1024 + 512L * 1024 * 1024);

        // When
        double result = sut.toGiB();

        // Then
        assertThat(result).isEqualTo(1.5);
    }

    @Test
    void toGiB_zeroBytes_isZero() {
        // Given
        MemorySize sut = new MemorySize(0);

        // When
        double result = sut.toGiB();

        // Then
        assertThat(result).isZero();
    }
}
