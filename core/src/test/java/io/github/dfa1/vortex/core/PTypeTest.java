package io.github.dfa1.vortex.core;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class PTypeTest {

    @ParameterizedTest
    @CsvSource({
            "U8,  1", "I8,  1",
            "U16, 2", "I16, 2", "F16, 2",
            "U32, 4", "I32, 4", "F32, 4",
            "U64, 8", "I64, 8", "F64, 8",
    })
    void byteSize(PType ptype, int expected) {
        // Given / When / Then
        assertThat(ptype.byteSize()).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"F16", "F32", "F64"})
    void isFloating_trueForFloats(PType ptype) {
        // Given / When / Then
        assertThat(ptype.isFloating()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"U8", "U16", "U32", "U64", "I8", "I16", "I32", "I64"})
    void isFloating_falseForIntegers(PType ptype) {
        // Given / When / Then
        assertThat(ptype.isFloating()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "I16", "I32", "I64", "F16", "F32", "F64"})
    void isSigned_trueForSignedAndFloats(PType ptype) {
        // Given / When / Then
        assertThat(ptype.isSigned()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"U8", "U16", "U32", "U64"})
    void isSigned_falseForUnsigned(PType ptype) {
        // Given / When / Then
        assertThat(ptype.isSigned()).isFalse();
    }
}
