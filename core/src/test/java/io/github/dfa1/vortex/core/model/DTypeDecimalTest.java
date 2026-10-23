package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Rust's `DecimalDType::try_new` bounds. Before they lived on the record, the writer accepted
/// `DType.decimal(80, 0)` or `(10, 12)` and wrote a file that vortex-jni and our own reader refuse.
class DTypeDecimalTest {

    @ParameterizedTest
    @CsvSource({"1, 0", "76, 76", "10, -5", "5, -100", "38, 0"})
    void inRange_isAccepted(int precision, int scale) {
        // Given / When
        DType.Decimal result = DType.Decimal.of(precision, scale, true);

        // Then
        assertThat(result.precision()).isEqualTo((byte) precision);
        assertThat(result.scale()).isEqualTo((byte) scale);
        assertThat(result.nullable()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0",     // Rust's NonZero precision
            "77, 0",    // above i256's 76 digits
            "300, 2",   // must not truncate to (byte) 44, a valid precision
            "-1, 0",    // a proto u32 above 2^31 reads as a negative int
            "10, 12",   // scale above precision
            "10, -129", // must not truncate to (byte) 127
    })
    void outOfRange_throwsVortexException(int precision, int scale) {
        // Given / When / Then: VortexException, since the file parsers construct through here
        assertThatThrownBy(() -> DType.Decimal.of(precision, scale, false))
                .isInstanceOf(VortexException.class);
    }

    @Test
    void constructor_enforcesTheSameBounds() {
        // Given a byte precision of 200 — negative as a byte, and never valid
        byte precision = (byte) 200;

        // When / Then
        assertThatThrownBy(() -> new DType.Decimal(precision, (byte) 0, false))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("out of range");
    }

    @ParameterizedTest(name = "precision {0} -> {1} bytes")
    @CsvSource({"1, 1", "2, 1", "3, 2", "4, 2", "5, 4", "9, 4", "10, 8", "18, 8", "19, 16", "38, 16", "39, 32", "76, 32"})
    void byteWidth_matchesRustPrecisionBoundaries(int precision, int expectedBytes) {
        // Given: Rust stores a decimal in the smallest integer its precision fits; the writer packs
        // and the reader sizes placeholders by this, so a wrong edge misreads every value
        DType.Decimal sut = DType.Decimal.of(precision, 0, false);

        // When
        int result = sut.byteWidth();

        // Then
        assertThat(result).isEqualTo(expectedBytes);
    }

    @Test
    void factory_enforcesTheSameBounds() {
        // Given / When / Then: the public decimal(int, int) shortcut the writer's users call
        assertThatThrownBy(() -> DType.decimal(10, 12))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("exceeds precision 10");
    }
}
